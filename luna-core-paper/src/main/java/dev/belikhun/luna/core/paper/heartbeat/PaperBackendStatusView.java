package dev.belikhun.luna.core.paper.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec.HeartbeatSnapshotPayload;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

public final class PaperBackendStatusView implements BackendStatusView {
	private final ConcurrentMap<String, BackendServerStatus> statuses;
	private final Set<Runnable> updateListeners;
	private volatile BackendMetadata currentBackendMetadata;

	public PaperBackendStatusView() {
		this.statuses = new ConcurrentHashMap<>();
		this.updateListeners = ConcurrentHashMap.newKeySet();
		this.currentBackendMetadata = null;
	}

	public void updateSnapshot(HeartbeatSnapshotPayload payload) {
		boolean hadStatuses = !statuses.isEmpty();
		BackendMetadata previousMetadata = currentBackendMetadata;
		statuses.clear();
		currentBackendMetadata = payload == null ? null : sanitizeMetadata(payload.currentBackendMetadata());
		if (payload == null || payload.statuses() == null || payload.statuses().isEmpty()) {
			if (hadStatuses || !sameMetadata(previousMetadata, currentBackendMetadata)) {
				notifyUpdated();
			}
			return;
		}

		for (Map.Entry<String, BackendServerStatus> entry : payload.statuses().entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			statuses.put(normalize(entry.getKey()), entry.getValue());
		}
		notifyUpdated();
	}

	public void updateSnapshot(Map<String, BackendServerStatus> snapshot) {
		boolean hadStatuses = !statuses.isEmpty();
		statuses.clear();
		if (snapshot == null || snapshot.isEmpty()) {
			if (hadStatuses) {
				notifyUpdated();
			}
			return;
		}

		for (Map.Entry<String, BackendServerStatus> entry : snapshot.entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			statuses.put(normalize(entry.getKey()), entry.getValue());
		}
		notifyUpdated();
	}

	public void applyDelta(HeartbeatSnapshotPayload payload) {
		if (payload == null || payload.deltas() == null || payload.deltas().isEmpty()) {
			BackendMetadata previousMetadata = currentBackendMetadata;
			BackendMetadata metadata = payload == null ? null : sanitizeMetadata(payload.currentBackendMetadata());
			if (metadata != null) {
				currentBackendMetadata = metadata;
				if (!sameMetadata(previousMetadata, currentBackendMetadata)) {
					notifyUpdated();
				}
			}
			return;
		}

		for (Map.Entry<String, dev.belikhun.luna.core.api.heartbeat.BackendServerStatusDelta> entry : payload.deltas().entrySet()) {
			dev.belikhun.luna.core.api.heartbeat.BackendServerStatusDelta delta = entry.getValue();
			if (delta == null) {
				continue;
			}
			String normalizedName = normalize(entry.getKey());
			BackendServerStatus merged = delta.applyTo(statuses.get(normalizedName));
			statuses.put(normalizedName, merged);
			if (delta.self()) {
				currentBackendMetadata = sanitizeMetadata(merged.metadata());
			}
		}

		BackendMetadata metadata = sanitizeMetadata(payload.currentBackendMetadata());
		if (metadata != null) {
			currentBackendMetadata = metadata;
		}
		notifyUpdated();
	}

	public void applyDelta(Map<String, BackendServerStatus> delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}

		for (Map.Entry<String, BackendServerStatus> entry : delta.entrySet()) {
			BackendServerStatus status = entry.getValue();
			if (status == null) {
				continue;
			}
			statuses.put(normalize(entry.getKey()), status);
		}
		notifyUpdated();
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
		if (serverName == null || serverName.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(statuses.get(normalize(serverName)));
	}

	@Override
	public Map<String, BackendServerStatus> snapshot() {
		return new LinkedHashMap<>(statuses);
	}

	@Override
	public Optional<BackendMetadata> currentBackendMetadata() {
		return Optional.ofNullable(currentBackendMetadata);
	}

	private String normalize(String serverName) {
		return serverName == null ? "" : serverName.trim().toLowerCase(Locale.ROOT);
	}

	private BackendMetadata sanitizeMetadata(BackendMetadata metadata) {
		if (metadata == null) {
			return null;
		}

		BackendMetadata sanitized = metadata.sanitize();
		return sanitized.isBlank() ? null : sanitized;
	}

	private boolean sameMetadata(BackendMetadata left, BackendMetadata right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return left.sanitize().equals(right.sanitize());
	}

	private void notifyUpdated() {
		for (Runnable listener : updateListeners) {
			try {
				listener.run();
			} catch (Exception ignored) {
			}
		}
	}
}
