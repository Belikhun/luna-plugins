package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class VelocityBackendStatusRegistry implements BackendStatusView {
	private final ConcurrentMap<String, BackendServerStatus> statuses;
	private volatile long timeoutMillis;

	public VelocityBackendStatusRegistry(long timeoutMillis) {
		this.statuses = new ConcurrentHashMap<>();
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
	}

	public void updateTimeoutMillis(long timeoutMillis) {
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
	}

	public BackendServerStatus upsert(String serverName, BackendHeartbeatStats stats, long nowEpochMillis) {
		String normalized = normalizeKey(serverName);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("serverName cannot be blank");
		}

		BackendServerStatus stored = new BackendServerStatus(serverName.trim(), true, nowEpochMillis, stats);
		statuses.put(normalized, stored);
		return toSnapshot(stored, nowEpochMillis);
	}

	@Override
	public Optional<BackendServerStatus> status(String serverName) {
		String normalized = normalizeKey(serverName);
		if (normalized.isBlank()) {
			return Optional.empty();
		}

		BackendServerStatus stored = statuses.get(normalized);
		if (stored == null) {
			return Optional.empty();
		}

		long now = System.currentTimeMillis();
		return Optional.of(toSnapshot(stored, now));
	}

	@Override
	public Map<String, BackendServerStatus> snapshot() {
		long now = System.currentTimeMillis();
		Map<String, BackendServerStatus> out = new LinkedHashMap<>();
		for (BackendServerStatus stored : statuses.values()) {
			BackendServerStatus snapshotStatus = toSnapshot(stored, now);
			out.put(normalizeKey(snapshotStatus.serverName()), snapshotStatus);
		}
		return out;
	}

	private BackendServerStatus toSnapshot(BackendServerStatus stored, long nowEpochMillis) {
		boolean online = nowEpochMillis - stored.lastHeartbeatEpochMillis() <= timeoutMillis;
		return new BackendServerStatus(
			stored.serverName(),
			online,
			stored.lastHeartbeatEpochMillis(),
			stored.stats()
		);
	}

	private String normalizeKey(String serverName) {
		if (serverName == null) {
			return "";
		}
		return serverName.trim().toLowerCase(Locale.ROOT);
	}
}
