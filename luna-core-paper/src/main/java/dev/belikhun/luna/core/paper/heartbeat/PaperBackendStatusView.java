package dev.belikhun.luna.core.paper.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;

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

	public PaperBackendStatusView() {
		this.statuses = new ConcurrentHashMap<>();
		this.updateListeners = ConcurrentHashMap.newKeySet();
	}

	public void updateSnapshot(Map<String, BackendServerStatus> snapshot) {
		statuses.clear();
		if (snapshot == null || snapshot.isEmpty()) {
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

	private String normalize(String serverName) {
		return serverName == null ? "" : serverName.trim().toLowerCase(Locale.ROOT);
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
