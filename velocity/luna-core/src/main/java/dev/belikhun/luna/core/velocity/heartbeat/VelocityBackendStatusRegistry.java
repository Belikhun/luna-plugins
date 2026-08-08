package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.event.LunaEventManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEvent;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEventEmitter;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEventType;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatListener;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusRow;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cluster's only authoritative view of which backends are up and what they
 * report.
 *
 * Three properties the previous implementation lacked and the whole sync design
 * now rests on:
 *
 *  - **Rows are whole and versioned together.** One map of immutable
 *    {@link BackendStatusRow}s, so a reader can never see a revision that
 *    belongs to a different row than the status it reads.
 *  - **Reads are pure.** Timeouts are swept by the scheduled sweeper only.
 *    Reading the registry from a placeholder or command thread used to sweep,
 *    flip a backend offline and dispatch listeners inline.
 *  - **Events are dispatched off the caller's thread**, because a listener
 *    broadcasts to every stream subscriber.
 *
 * The registry outlives a config reload; only a proxy restart mints a new
 * {@link #epoch()}, which is how backends learn their cursors are meaningless.
 */
public final class VelocityBackendStatusRegistry implements BackendStatusView, BackendHeartbeatEventEmitter {
	private final ConcurrentMap<String, BackendStatusRow> rows;
	private final LunaEventManager eventManager;
	private final LunaLogger logger;
	private final AtomicLong revisionCounter;
	private final ExecutorService eventDispatcher;
	private final String epoch;
	private volatile long timeoutMillis;

	public VelocityBackendStatusRegistry(long timeoutMillis, LunaLogger logger) {
		this.rows = new ConcurrentHashMap<>();
		this.eventManager = new LunaEventManager();
		this.logger = logger.scope("HeartbeatRegistry");
		this.revisionCounter = new AtomicLong(0L);
		this.epoch = UUID.randomUUID().toString();
		this.timeoutMillis = Math.max(1000L, timeoutMillis);

		this.eventDispatcher = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-heartbeat-events");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void updateTimeoutMillis(long timeoutMillis) {
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
	}

	/** Identifies this registry generation; changes only when the proxy restarts. */
	public String epoch() {
		return epoch;
	}

	public BackendServerStatus upsert(BackendMetadata metadata, BackendHeartbeatStats stats, long nowEpochMillis) {
		return update(metadata, stats, nowEpochMillis, true);
	}

	public BackendServerStatus markOffline(BackendMetadata metadata, BackendHeartbeatStats stats, long nowEpochMillis) {
		return update(metadata, stats, nowEpochMillis, false);
	}

	private BackendServerStatus update(BackendMetadata metadata, BackendHeartbeatStats stats, long nowEpochMillis, boolean online) {
		BackendMetadata sanitizedMetadata = metadata == null ? new BackendMetadata("", "", "") : metadata.sanitize();
		String normalized = sanitizedMetadata.normalizedName();
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("serverName cannot be blank");
		}

		BackendStatusRow previousRow = rows.get(normalized);
		BackendServerStatus previous = previousRow == null ? null : previousRow.status();
		BackendMetadata resolvedMetadata = new BackendMetadata(
			sanitizedMetadata.name(),
			resolveDisplay(sanitizedMetadata, previous),
			resolveAccentColor(sanitizedMetadata, previous)
		).sanitize();
		BackendServerStatus stored = new BackendServerStatus(
			resolvedMetadata.name(),
			resolvedMetadata.displayName(),
			resolvedMetadata.accentColor(),
			online,
			nowEpochMillis,
			stats
		);

		rows.put(normalized, new BackendStatusRow(stored, revisionCounter.incrementAndGet()));

		if (online && (previous == null || !previous.online())) {
			logger.success("Backend online: " + stored.serverName());
			emit(BackendHeartbeatEventType.SERVER_ONLINE, stored, previous, nowEpochMillis);
		}

		if (!online && previous != null && previous.online()) {
			logger.warn("Backend offline: " + stored.serverName());
			emit(BackendHeartbeatEventType.SERVER_OFFLINE, stored, previous, nowEpochMillis);
		}

		logger.debug("Heartbeat nhận từ backend=" + stored.serverName() + " online=" + stored.online() + " players=" + stored.stats().onlinePlayers() + "/" + stored.stats().maxPlayers() + " tps=" + stored.stats().tps());
		emit(BackendHeartbeatEventType.HEARTBEAT_RECEIVED, stored, previous, nowEpochMillis);
		return stored;
	}

	/**
	 * Flip every backend that has stopped reporting to offline.
	 *
	 * Called only by the scheduled sweeper — a read must never mutate, or a TAB
	 * placeholder lookup ends up dispatching heartbeat events.
	 */
	public int sweepTimeouts(long nowEpochMillis) {
		if (rows.isEmpty()) {
			return 0;
		}

		List<BackendHeartbeatEvent> timeoutEvents = new ArrayList<>();
		for (Map.Entry<String, BackendStatusRow> entry : rows.entrySet()) {
			BackendStatusRow storedRow = entry.getValue();
			BackendServerStatus stored = storedRow.status();
			if (!stored.online()) {
				continue;
			}
			if (nowEpochMillis - stored.lastHeartbeatEpochMillis() <= timeoutMillis) {
				continue;
			}

			BackendServerStatus offline = new BackendServerStatus(
				stored.serverName(),
				stored.serverDisplay(),
				stored.serverAccentColor(),
				false,
				stored.lastHeartbeatEpochMillis(),
				stored.stats()
			);

			if (rows.replace(entry.getKey(), storedRow, new BackendStatusRow(offline, revisionCounter.incrementAndGet()))) {
				logger.warn("Backend offline do timeout: " + offline.serverName());
				timeoutEvents.add(new BackendHeartbeatEvent(BackendHeartbeatEventType.SERVER_OFFLINE, offline, stored, nowEpochMillis));
			}
		}

		for (BackendHeartbeatEvent event : timeoutEvents) {
			emit(event.type(), event.current(), event.previous(), event.atEpochMillis());
		}

		return timeoutEvents.size();
	}

	@Override
	public Optional<BackendServerStatus> status(String serverName) {
		String normalized = normalizeKey(serverName);
		if (normalized.isBlank()) {
			return Optional.empty();
		}

		BackendStatusRow row = rows.get(normalized);
		return Optional.ofNullable(row == null ? null : row.status());
	}

	@Override
	public Map<String, BackendServerStatus> snapshot() {
		Map<String, BackendServerStatus> out = new LinkedHashMap<>();
		for (BackendStatusRow row : rows.values()) {
			out.put(normalizeKey(row.serverName()), row.status());
		}
		return out;
	}

	/** Every row, oldest revision first — the body of a full sync. */
	public List<BackendStatusRow> allRows() {
		List<BackendStatusRow> out = new ArrayList<>(rows.values());
		out.sort(Comparator.comparingLong(BackendStatusRow::revision));
		return out;
	}

	/** One row, or null when the name is unknown. */
	public BackendStatusRow row(String serverName) {
		String normalized = normalizeKey(serverName);
		return normalized.isBlank() ? null : rows.get(normalized);
	}

	/**
	 * Every row written after {@code sinceRevision}, oldest first.
	 *
	 * Whole rows, never field diffs: a consumer that misses one response still
	 * converges on the next, because the row it missed is re-sent in full until
	 * its cursor passes it.
	 */
	public List<BackendStatusRow> rowsSince(long sinceRevision) {
		List<BackendStatusRow> out = new ArrayList<>();
		for (BackendStatusRow row : rows.values()) {
			if (row.revision() > sinceRevision) {
				out.add(row);
			}
		}
		out.sort(Comparator.comparingLong(BackendStatusRow::revision));
		return out;
	}

	public long currentRevision() {
		return Math.max(0L, revisionCounter.get());
	}

	@Override
	public void addHeartbeatListener(BackendHeartbeatListener listener) {
		eventManager.registerListener(BackendHeartbeatEvent.class, listener);
	}

	@Override
	public void removeHeartbeatListener(BackendHeartbeatListener listener) {
		eventManager.unregisterListener(BackendHeartbeatEvent.class, listener);
	}

	/** Stop the event dispatcher; the registry is unusable afterwards. */
	public void shutdown() {
		eventDispatcher.shutdownNow();
	}

	private void emit(BackendHeartbeatEventType type, BackendServerStatus current, BackendServerStatus previous, long atEpochMillis) {
		BackendHeartbeatEvent event = new BackendHeartbeatEvent(type, current, previous, atEpochMillis);
		try {
			eventDispatcher.execute(() -> {
				try {
					eventManager.dispatchEvent(event);
				} catch (Exception exception) {
					logger.warn("Lỗi dispatch heartbeat event: " + exception.getMessage());
				}
			});
		} catch (Exception exception) {
			logger.warn("Không thể xếp hàng heartbeat event: " + exception.getMessage());
		}
	}

	private String normalizeKey(String serverName) {
		if (serverName == null) {
			return "";
		}
		return serverName.trim().toLowerCase(Locale.ROOT);
	}

	private String resolveDisplay(BackendMetadata metadata, BackendServerStatus previous) {
		if (metadata != null && metadata.displayName() != null && !metadata.displayName().isBlank()) {
			return metadata.displayName().trim();
		}
		if (previous != null && previous.serverDisplay() != null && !previous.serverDisplay().isBlank()) {
			return previous.serverDisplay();
		}
		return metadata == null || metadata.name() == null ? "" : metadata.name().trim();
	}

	private String resolveAccentColor(BackendMetadata metadata, BackendServerStatus previous) {
		if (metadata != null && metadata.accentColor() != null && !metadata.accentColor().isBlank()) {
			return metadata.accentColor().trim();
		}
		if (previous != null && previous.serverAccentColor() != null && !previous.serverAccentColor().isBlank()) {
			return previous.serverAccentColor();
		}
		return "";
	}
}
