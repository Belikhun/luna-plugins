package dev.belikhun.luna.core.velocity.dashboard;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEvent;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.ServerWorldStats;
import dev.belikhun.luna.core.api.heartbeat.ServerTickStats;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.http.SseBroadcaster;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.serverselector.ServerSelectorEngine;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.serverselector.ServerSelectorStatus;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only network telemetry for the control console.
 *
 * Every route is token-gated like the heartbeat endpoints: the payload exposes
 * player counts, MOTDs and host names, which is not public information even on a
 * LAN. `/dashboard/stream` is the push counterpart of `/dashboard/backends` —
 * subscribers get a full snapshot on connect and then one event per heartbeat, so
 * the console never has to poll.
 */
public final class VelocityDashboardHttpEndpoints {
	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityServerSelectorConfig selectorConfig;
	private final RequestAuthorizer authorizer;
	private final SseBroadcaster broadcaster;

	public VelocityDashboardHttpEndpoints(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityServerSelectorConfig selectorConfig,
		RequestAuthorizer authorizer,
		SseBroadcaster broadcaster
	) {
		this.logger = logger.scope("DashboardHttp");
		this.statusRegistry = statusRegistry;
		this.selectorConfig = selectorConfig;
		this.authorizer = authorizer;
		this.broadcaster = broadcaster;
	}

	public void register(Router router) {
		router.get("/dashboard/backends", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /dashboard/backends do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			return LunaJson.envelope(200, buildSnapshot(), startedAt);
		});

		router.get("/dashboard/backends/{server}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /dashboard/backends/{server} do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String serverName = normalize(request.pathParam("server", ""));
			if (serverName.isBlank()) {
				return LunaJson.error(400, "server name is required");
			}

			Map<String, BackendServerStatus> statuses = resolvedStatuses();
			BackendServerStatus status = statuses.get(serverName);
			if (status == null) {
				logger.debug("Không tìm thấy backend dashboard cho " + serverName);
				return LunaJson.error(404, "backend not found: " + serverName);
			}

			return LunaJson.envelope(200, buildBackendDetail(status), startedAt);
		});

		router.get("/dashboard/stream", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /dashboard/stream do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			logger.debug("Console mở stream telemetry.");
			return broadcaster.subscribe(stream -> stream.event("snapshot", LunaJson.write(buildSnapshot())));
		});
	}

	/**
	 * Push one backend's card to every console stream. Registered as a heartbeat
	 * listener, so this runs on the HTTP handler thread that accepted the heartbeat —
	 * it therefore does no work at all while nobody is subscribed.
	 */
	public void onHeartbeatEvent(BackendHeartbeatEvent event) {
		if (event == null || event.current() == null || broadcaster.size() == 0) {
			return;
		}

		Map<String, BackendServerStatus> statuses = resolvedStatuses();
		BackendServerStatus current = statuses.getOrDefault(
			normalize(event.current().serverName()),
			event.current()
		);
		ServerSelectorEngine.DashboardStats dashboardStats = ServerSelectorEngine.dashboardStats(statuses);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("generatedAtEpochMillis", System.currentTimeMillis());
		payload.put("overallHealth", overallHealth(statuses, dashboardStats));
		payload.put("counts", buildCounts(statuses));
		payload.put("summary", buildSummary(dashboardStats));
		payload.put("backend", buildBackendCard(current));

		broadcaster.broadcast(eventName(event), LunaJson.write(payload));
	}

	private String eventName(BackendHeartbeatEvent event) {
		return switch (event.type()) {
			case SERVER_ONLINE -> "backend-online";
			case SERVER_OFFLINE -> "backend-offline";
			default -> "backend";
		};
	}

	/** The full network snapshot, shared by the polled route and the stream's first event. */
	private Map<String, Object> buildSnapshot() {
		Map<String, BackendServerStatus> statuses = resolvedStatuses();
		ServerSelectorEngine.DashboardStats dashboardStats = ServerSelectorEngine.dashboardStats(statuses);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("generatedAtEpochMillis", System.currentTimeMillis());
		payload.put("overallHealth", overallHealth(statuses, dashboardStats));
		payload.put("counts", buildCounts(statuses));
		payload.put("summary", buildSummary(dashboardStats));
		payload.put("backends", buildBackendCards(statuses));
		return payload;
	}

	private Map<String, BackendServerStatus> resolvedStatuses() {
		Map<String, BackendServerStatus> resolved = new LinkedHashMap<>();

		for (String knownServer : selectorConfig.knownServerNames()) {
			String normalized = normalize(knownServer);
			if (normalized.isBlank()) {
				continue;
			}

			BackendMetadata metadata = selectorConfig.backendMetadata(normalized);
			resolved.put(normalized, offlineStatus(metadata));
		}

		for (Map.Entry<String, BackendServerStatus> entry : statusRegistry.snapshot().entrySet()) {
			String normalized = normalize(entry.getKey());
			BackendServerStatus status = entry.getValue();
			if (normalized.isBlank() || status == null) {
				continue;
			}

			resolved.put(normalized, mergeWithSelectorMetadata(normalized, status));
		}

		return resolved;
	}

	private BackendServerStatus mergeWithSelectorMetadata(String normalized, BackendServerStatus status) {
		BackendMetadata metadata = selectorConfig.backendMetadata(normalized);
		String displayName = firstNonBlank(metadata.displayName(), status.serverDisplay(), status.serverName(), normalized);
		String accentColor = firstNonBlank(metadata.accentColor(), status.serverAccentColor(), "");
		return new BackendServerStatus(
			firstNonBlank(status.serverName(), metadata.name(), normalized),
			displayName,
			accentColor,
			status.online(),
			status.lastHeartbeatEpochMillis(),
			status.stats()
		);
	}

	private BackendServerStatus offlineStatus(BackendMetadata metadata) {
		BackendMetadata sanitized = metadata == null ? new BackendMetadata("", "", "").sanitize() : metadata.sanitize();
		String name = firstNonBlank(sanitized.name(), sanitized.serverName());
		return new BackendServerStatus(name, sanitized.displayName(), sanitized.accentColor(), false, 0L, null);
	}

	private Map<String, Object> buildCounts(Map<String, BackendServerStatus> statuses) {
		int online = 0;
		int maint = 0;
		int offline = 0;

		for (BackendServerStatus status : statuses.values()) {
			String resolvedStatus = resolvedStatus(status);
			switch (resolvedStatus) {
				case "ONLINE" -> online++;
				case "MAINT" -> maint++;
				default -> offline++;
			}
		}

		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put("total", statuses.size());
		counts.put("online", online);
		counts.put("maint", maint);
		counts.put("offline", offline);
		return counts;
	}

	private Map<String, Object> buildSummary(ServerSelectorEngine.DashboardStats dashboardStats) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("onlinePlayers", dashboardStats.totalOnlinePlayers());
		summary.put("onlineServerCount", dashboardStats.onlineServerCount());
		summary.put("averageTps", LunaJson.round(dashboardStats.averageTps()));
		summary.put("averageCpu", LunaJson.round(dashboardStats.averageCpu()));
		summary.put("averageLatencyMillis", LunaJson.round(dashboardStats.averageLatency()));
		summary.put("totalRamUsedBytes", dashboardStats.totalRamUsedBytes());
		summary.put("totalRamMaxBytes", dashboardStats.totalRamMaxBytes());
		summary.put("longestUptimeMillis", dashboardStats.maxUptimeMillis());
		return summary;
	}

	private List<Map<String, Object>> buildBackendCards(Map<String, BackendServerStatus> statuses) {
		List<BackendServerStatus> ordered = new ArrayList<>(statuses.values());
		ordered.sort(Comparator.comparing(status -> normalize(status.serverName())));

		List<Map<String, Object>> cards = new ArrayList<>(ordered.size());
		for (BackendServerStatus status : ordered) {
			cards.add(buildBackendCard(status));
		}
		return List.copyOf(cards);
	}

	private Map<String, Object> buildBackendCard(BackendServerStatus status) {
		BackendHeartbeatStats stats = status.stats();
		VelocityServerSelectorConfig.ServerDefinition definition = selectorConfig.server(status.serverName());
		String statusText = resolvedStatus(status);

		Map<String, Object> card = new LinkedHashMap<>();
		card.put("id", normalize(status.serverName()));
		card.put("name", status.serverName());
		card.put("displayName", status.serverDisplay());
		card.put("accentColor", safe(status.serverAccentColor()));
		card.put("hostName", hostName(status.serverName()));
		card.put("status", statusText);
		card.put("statusIcon", selectorConfig.icon(serverSelectorStatus(statusText)));
		card.put("statusColor", selectorConfig.color(serverSelectorStatus(statusText)));
		card.put("online", status.online());
		card.put("lastHeartbeatEpochMillis", status.lastHeartbeatEpochMillis());
		card.put("description", description(definition, statusText));
		card.put("metrics", buildMetrics(stats));
		return card;
	}

	private Map<String, Object> buildBackendDetail(BackendServerStatus status) {
		BackendHeartbeatStats stats = status.stats();
		Map<String, Object> detail = new LinkedHashMap<>(buildBackendCard(status));
		detail.put("stats", buildStats(stats));
		return detail;
	}

	private Map<String, Object> buildMetrics(BackendHeartbeatStats stats) {
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("onlinePlayers", stats == null ? 0 : Math.max(0, stats.onlinePlayers()));
		metrics.put("maxPlayers", stats == null ? 0 : Math.max(0, stats.maxPlayers()));
		metrics.put("playerUsagePercent", stats == null ? 0D : LunaJson.round(percent(stats.onlinePlayers(), stats.maxPlayers())));
		metrics.put("tps", stats == null ? 0D : LunaJson.round(stats.tps()));
		metrics.put("systemCpuUsagePercent", stats == null ? 0D : LunaJson.round(stats.systemCpuUsagePercent()));
		metrics.put("processCpuUsagePercent", stats == null ? 0D : LunaJson.round(stats.processCpuUsagePercent()));
		metrics.put("heartbeatLatencyMillis", stats == null ? 0L : Math.max(0L, stats.heartbeatLatencyMillis()));
		metrics.put("ramUsedBytes", stats == null ? 0L : Math.max(0L, stats.ramUsedBytes()));
		metrics.put("ramFreeBytes", stats == null ? 0L : Math.max(0L, stats.ramFreeBytes()));
		metrics.put("ramMaxBytes", stats == null ? 0L : Math.max(0L, stats.ramMaxBytes()));
		metrics.put("ramUsagePercent", stats == null ? 0D : LunaJson.round(percent(stats.ramUsedBytes(), stats.ramMaxBytes())));
		metrics.put("uptimeMillis", stats == null ? 0L : Math.max(0L, stats.uptimeMillis()));
		metrics.put("whitelistEnabled", stats != null && stats.whitelistEnabled());

		// null rather than 0 throughout: a backend on a platform that cannot count
		// its chunks, or one whose plugin predates these fields, has not measured
		// them, and a zero here would draw an empty world in the console
		metrics.put("loadedChunks", count(stats == null ? null : stats.loadedChunks()));
		metrics.put("tickingEntities", count(stats == null ? null : stats.tickingEntities()));
		metrics.put("nonTickingEntities", count(stats == null ? null : stats.nonTickingEntities()));

		ServerTickStats ticks = stats == null ? ServerTickStats.UNKNOWN : stats.ticks();
		boolean measured = ticks.known();
		metrics.put("tickMeanMillis", measured ? LunaJson.round(ticks.meanMillis()) : null);
		metrics.put("tickMaxMillis", measured ? LunaJson.round(ticks.maxMillis()) : null);
		metrics.put("apdex", measured ? LunaJson.round(ticks.apdex()) : null);
		metrics.put("misery", ticks.miseryKnown() ? LunaJson.round(ticks.misery()) : null);

		metrics.put("worlds", buildWorlds(stats));
		return metrics;
	}

	/** A counter the backend could not measure comes back as JSON null. */
	private Object count(Integer value) {
		return value == null || value < 0 ? null : value;
	}

	private List<Map<String, Object>> buildWorlds(BackendHeartbeatStats stats) {
		List<Map<String, Object>> worlds = new ArrayList<>();

		if (stats == null) {
			return worlds;
		}

		for (ServerWorldStats world : stats.worlds()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", safe(world.name()));
			row.put("loadedChunks", count(world.loadedChunks()));
			row.put("tickingEntities", count(world.tickingEntities()));
			row.put("nonTickingEntities", count(world.nonTickingEntities()));
			worlds.add(row);
		}

		return worlds;
	}

	private Map<String, Object> buildStats(BackendHeartbeatStats stats) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (stats == null) {
			payload.put("software", "");
			payload.put("version", "");
			payload.put("serverPort", 0);
			payload.put("uptimeMillis", 0L);
			payload.put("tps", 0D);
			payload.put("onlinePlayers", 0);
			payload.put("maxPlayers", 0);
			payload.put("motd", "");
			payload.put("whitelistEnabled", false);
			payload.put("systemCpuUsagePercent", 0D);
			payload.put("processCpuUsagePercent", 0D);
			payload.put("ramUsedBytes", 0L);
			payload.put("ramFreeBytes", 0L);
			payload.put("ramMaxBytes", 0L);
			payload.put("heartbeatLatencyMillis", 0L);
			return payload;
		}

		payload.put("software", safe(stats.software()));
		payload.put("version", safe(stats.version()));
		payload.put("serverPort", stats.serverPort());
		payload.put("uptimeMillis", Math.max(0L, stats.uptimeMillis()));
		payload.put("tps", LunaJson.round(stats.tps()));
		payload.put("onlinePlayers", Math.max(0, stats.onlinePlayers()));
		payload.put("maxPlayers", Math.max(0, stats.maxPlayers()));
		payload.put("motd", safe(stats.motd()));
		payload.put("whitelistEnabled", stats.whitelistEnabled());
		payload.put("systemCpuUsagePercent", LunaJson.round(stats.systemCpuUsagePercent()));
		payload.put("processCpuUsagePercent", LunaJson.round(stats.processCpuUsagePercent()));
		payload.put("ramUsedBytes", Math.max(0L, stats.ramUsedBytes()));
		payload.put("ramFreeBytes", Math.max(0L, stats.ramFreeBytes()));
		payload.put("ramMaxBytes", Math.max(0L, stats.ramMaxBytes()));
		payload.put("heartbeatLatencyMillis", Math.max(0L, stats.heartbeatLatencyMillis()));
		return payload;
	}

	private String overallHealth(Map<String, BackendServerStatus> statuses, ServerSelectorEngine.DashboardStats dashboardStats) {
		if (statuses.isEmpty() || dashboardStats.onlineServerCount() == 0) {
			return "critical";
		}

		int offlineCount = 0;
		for (BackendServerStatus status : statuses.values()) {
			if ("OFFLINE".equals(resolvedStatus(status))) {
				offlineCount++;
			}
		}

		if (dashboardStats.averageTps() < 15D || dashboardStats.averageCpu() >= 90D || dashboardStats.averageLatency() >= 300D) {
			return "critical";
		}

		if (offlineCount > 0 || dashboardStats.averageTps() < 18D || dashboardStats.averageCpu() >= 75D || dashboardStats.averageLatency() >= 180D) {
			return "degraded";
		}

		return "healthy";
	}

	private List<String> description(VelocityServerSelectorConfig.ServerDefinition definition, String statusText) {
		if (definition == null) {
			return List.of();
		}

		ServerSelectorStatus status = serverSelectorStatus(statusText);
		List<String> statusSpecific = definition.descriptionByStatus().get(status);
		if (statusSpecific != null && !statusSpecific.isEmpty()) {
			return statusSpecific;
		}

		List<String> description = definition.description();
		return description == null ? List.of() : description;
	}

	private String hostName(String serverName) {
		BackendMetadata metadata = selectorConfig.backendMetadata(serverName);
		return firstNonBlank(metadata.serverName(), serverName);
	}

	private String resolvedStatus(BackendServerStatus status) {
		return ServerSelectorEngine.resolveStatus(status, false);
	}

	private ServerSelectorStatus serverSelectorStatus(String value) {
		try {
			return ServerSelectorStatus.valueOf(safe(value).toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return ServerSelectorStatus.OFFLINE;
		}
	}

	private double percent(long value, long max) {
		if (max <= 0L) {
			return 0D;
		}
		return Math.min(100D, Math.max(0D, (value * 100D) / max));
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
