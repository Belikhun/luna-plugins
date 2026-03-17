package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.event.LunaEventManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEvent;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEventEmitter;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEventType;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatListener;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatusDelta;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VelocityBackendStatusRegistry implements BackendStatusView, BackendHeartbeatEventEmitter {
	private final ConcurrentMap<String, BackendServerStatus> statuses;
	private final LunaEventManager eventManager;
	private final LunaLogger logger;
	private final ConcurrentMap<String, Long> statusRevisions;
	private final ConcurrentMap<String, BackendServerStatusDelta> statusDeltas;
	private final AtomicLong revisionCounter;
	private volatile long timeoutMillis;

	public VelocityBackendStatusRegistry(long timeoutMillis, LunaLogger logger) {
		this.statuses = new ConcurrentHashMap<>();
		this.eventManager = new LunaEventManager();
		this.logger = logger.scope("HeartbeatRegistry");
		this.statusRevisions = new ConcurrentHashMap<>();
		this.statusDeltas = new ConcurrentHashMap<>();
		this.revisionCounter = new AtomicLong(0L);
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
	}

	public void updateTimeoutMillis(long timeoutMillis) {
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
	}

	public BackendServerStatus upsert(BackendMetadata metadata, BackendHeartbeatStats stats, long nowEpochMillis) {
		return update(metadata, stats, nowEpochMillis, true);
	}

	public BackendServerStatus markOffline(BackendMetadata metadata, BackendHeartbeatStats stats, long nowEpochMillis) {
		return update(metadata, stats, nowEpochMillis, false);
	}

	private BackendServerStatus update(BackendMetadata metadata, BackendHeartbeatStats stats, long nowEpochMillis, boolean online) {
		sweepTimeouts(nowEpochMillis);

		BackendMetadata sanitizedMetadata = metadata == null ? new BackendMetadata("", "", "") : metadata.sanitize();
		String normalized = sanitizedMetadata.normalizedName();
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("serverName cannot be blank");
		}

		BackendServerStatus previous = statuses.get(normalized);
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
		BackendServerStatusDelta delta = BackendServerStatusDelta.diff(previous, stored);
		statuses.put(normalized, stored);
		long revision = revisionCounter.incrementAndGet();
		statusRevisions.put(normalized, revision);
		statusDeltas.put(normalized, delta);

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

	public int sweepTimeouts(long nowEpochMillis) {
		if (statuses.isEmpty()) {
			return 0;
		}

		List<BackendHeartbeatEvent> timeoutEvents = new ArrayList<>();
		for (Map.Entry<String, BackendServerStatus> entry : statuses.entrySet()) {
			BackendServerStatus stored = entry.getValue();
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

			if (statuses.replace(entry.getKey(), stored, offline)) {
				statusDeltas.put(entry.getKey(), BackendServerStatusDelta.diff(stored, offline));
				long revision = revisionCounter.incrementAndGet();
				statusRevisions.put(entry.getKey(), revision);
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
		sweepTimeouts(System.currentTimeMillis());

		String normalized = normalizeKey(serverName);
		if (normalized.isBlank()) {
			return Optional.empty();
		}

		return Optional.ofNullable(statuses.get(normalized));
	}

	@Override
	public Map<String, BackendServerStatus> snapshot() {
		sweepTimeouts(System.currentTimeMillis());

		Map<String, BackendServerStatus> out = new LinkedHashMap<>();
		for (BackendServerStatus stored : statuses.values()) {
			out.put(normalizeKey(stored.serverName()), stored);
		}
		return out;
	}

	public Map<String, BackendServerStatus> deltaSince(long sinceRevision) {
		sweepTimeouts(System.currentTimeMillis());

		Map<String, BackendServerStatus> out = new LinkedHashMap<>();
		for (Map.Entry<String, BackendServerStatus> entry : statuses.entrySet()) {
			Long revision = statusRevisions.get(entry.getKey());
			if (revision == null || revision <= sinceRevision) {
				continue;
			}
			out.put(entry.getKey(), entry.getValue());
		}
		return out;
	}

	public Map<String, BackendServerStatusDelta> deltaFieldsSince(long sinceRevision) {
		sweepTimeouts(System.currentTimeMillis());

		Map<String, BackendServerStatusDelta> out = new LinkedHashMap<>();
		for (Map.Entry<String, BackendServerStatusDelta> entry : statusDeltas.entrySet()) {
			Long revision = statusRevisions.get(entry.getKey());
			if (revision == null || revision <= sinceRevision) {
				continue;
			}
			out.put(entry.getKey(), entry.getValue());
		}
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

	private void emit(BackendHeartbeatEventType type, BackendServerStatus current, BackendServerStatus previous, long atEpochMillis) {
		BackendHeartbeatEvent event = new BackendHeartbeatEvent(type, current, previous, atEpochMillis);
		try {
			eventManager.dispatchEvent(event);
		} catch (Exception exception) {
			logger.warn("Lỗi dispatch heartbeat event: " + exception.getMessage());
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
