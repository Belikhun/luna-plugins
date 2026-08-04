package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec.HeartbeatSnapshotPayload;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * A backend's mirror of the proxy registry, shared by every platform.
 *
 * Rows arrive on two channels — the heartbeat response and the registry stream —
 * and both are whole rows, so applying one is a plain put and the order the two
 * channels interleave in does not matter.
 *
 * Only the heartbeat response moves the poll cursor. A stream event carries the
 * registry revision at the moment it was produced, so honouring it as a cursor
 * would skip every row that changed in between and had not reached this backend
 * yet. Leaving the cursor to the poll costs one redundant row per change and
 * makes a gap impossible.
 */
public final class BackendStatusStore implements BackendStatusView {
	private final ConcurrentMap<String, BackendServerStatus> statuses;
	private final Set<Runnable> updateListeners;
	private final Executor dispatcher;
	private final LunaLogger logger;
	private volatile BackendMetadata currentBackendMetadata;
	private volatile String epoch;
	private volatile long pollCursor;

	/**
	 * @param dispatcher runs update listeners; platforms pass their main-thread
	 *                   hopper, because a listener re-renders open inventories
	 */
	public BackendStatusStore(LunaLogger logger, Executor dispatcher) {
		this.statuses = new ConcurrentHashMap<>();
		this.updateListeners = ConcurrentHashMap.newKeySet();
		this.dispatcher = dispatcher == null ? Runnable::run : dispatcher;
		this.logger = logger.scope("StatusStore");
		this.currentBackendMetadata = null;
		this.epoch = "";
		this.pollCursor = -1L;
	}

	/** The `since` value for the next heartbeat; -1 asks for a full sync. */
	public long pollCursor() {
		return pollCursor;
	}

	/** The registry generation this mirror belongs to; blank until the first sync. */
	public String epoch() {
		return epoch;
	}

	/** Drop the cursor so the next heartbeat re-syncs from scratch. */
	public void resetCursor() {
		pollCursor = -1L;
	}

	/**
	 * Merge a decoded payload into the mirror.
	 *
	 * @param advanceCursor true for a heartbeat response, false for a stream event
	 * @return whether anything visible to a renderer changed
	 */
	public boolean apply(HeartbeatSnapshotPayload payload, boolean advanceCursor) {
		if (payload == null) {
			return false;
		}

		if (payload.protocol() != HeartbeatFormCodec.PROTOCOL_VERSION) {
			logger.error("Proxy nói protocol=" + payload.protocol()
				+ " nhưng backend dùng protocol=" + HeartbeatFormCodec.PROTOCOL_VERSION
				+ ". Hãy cập nhật LunaCore đồng bộ trên toàn cụm.");
			return false;
		}

		boolean changed = false;
		String incomingEpoch = payload.epoch() == null ? "" : payload.epoch();
		boolean epochChanged = !incomingEpoch.isBlank() && !incomingEpoch.equals(epoch);

		// a new epoch means the proxy restarted: its revisions restart with it, so
		// every row this mirror holds is from a registry that no longer exists
		if (epochChanged) {
			logger.info("Registry epoch đổi: " + (epoch.isBlank() ? "<mới>" : epoch) + " -> " + incomingEpoch);
			epoch = incomingEpoch;
		}

		if (payload.fullSync() || epochChanged) {
			changed = !statuses.isEmpty();
			statuses.clear();
		}

		for (BackendStatusRow row : payload.rows()) {
			if (row == null || row.status() == null) {
				continue;
			}

			String key = normalize(row.serverName());
			if (key.isBlank()) {
				continue;
			}

			BackendServerStatus previous = statuses.put(key, row.status());
			changed = changed || !row.status().equals(previous);

			if (row.self()) {
				currentBackendMetadata = sanitizeMetadata(row.status().metadata());
			}
		}

		BackendMetadata metadata = sanitizeMetadata(payload.currentBackendMetadata());
		if (metadata != null && !metadata.equals(currentBackendMetadata)) {
			currentBackendMetadata = metadata;
			changed = true;
		}

		if (advanceCursor) {
			pollCursor = Math.max(pollCursor, payload.revision());
		}

		if (changed) {
			notifyUpdated();
		}

		return changed;
	}

	public void addUpdateListener(Runnable listener) {
		if (listener == null) {
			return;
		}

		updateListeners.add(listener);
	}

	public void removeUpdateListener(Runnable listener) {
		if (listener == null) {
			return;
		}

		updateListeners.remove(listener);
	}

	@Override
	public Optional<BackendServerStatus> status(String serverName) {
		String key = normalize(serverName);
		if (key.isBlank()) {
			return Optional.empty();
		}

		return Optional.ofNullable(statuses.get(key));
	}

	@Override
	public Map<String, BackendServerStatus> snapshot() {
		return new LinkedHashMap<>(statuses);
	}

	@Override
	public Optional<BackendMetadata> currentBackendMetadata() {
		return Optional.ofNullable(currentBackendMetadata);
	}

	private void notifyUpdated() {
		if (updateListeners.isEmpty()) {
			return;
		}

		try {
			dispatcher.execute(() -> {
				for (Runnable listener : updateListeners) {
					try {
						listener.run();
					} catch (Exception exception) {
						// swallowing this used to hide broken GUI refreshes entirely
						logger.warn("Update listener lỗi: " + exception);
					}
				}
			});
		} catch (Exception exception) {
			// the platform scheduler refuses work while the server is shutting down,
			// and the last heartbeat of a shutdown goes through here
			logger.debug("Không thể lên lịch update listener: " + exception.getMessage());
		}
	}

	private BackendMetadata sanitizeMetadata(BackendMetadata metadata) {
		if (metadata == null) {
			return null;
		}

		BackendMetadata sanitized = metadata.sanitize();
		return sanitized.isBlank() ? null : sanitized;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
