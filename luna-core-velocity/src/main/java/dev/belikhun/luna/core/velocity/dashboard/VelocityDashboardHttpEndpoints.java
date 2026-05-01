package dev.belikhun.luna.core.velocity.dashboard;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.Router;
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

public final class VelocityDashboardHttpEndpoints {
	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityServerSelectorConfig selectorConfig;

	public VelocityDashboardHttpEndpoints(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityServerSelectorConfig selectorConfig
	) {
		this.logger = logger.scope("DashboardHttp");
		this.statusRegistry = statusRegistry;
		this.selectorConfig = selectorConfig;
	}

	public void register(Router router) {
		router.get("/dashboard/backends", request -> {
			long startedAt = System.nanoTime();
			Map<String, BackendServerStatus> statuses = resolvedStatuses();
			ServerSelectorEngine.DashboardStats dashboardStats = ServerSelectorEngine.dashboardStats(statuses);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("overallHealth", overallHealth(statuses, dashboardStats));
			payload.put("counts", buildCounts(statuses));
			payload.put("summary", buildSummary(dashboardStats));
			payload.put("backends", buildBackendCards(statuses));
			return jsonResponse(200, payload, startedAt);
		});

		router.get("/dashboard/backends/{server}", request -> {
			long startedAt = System.nanoTime();
			String serverName = normalize(request.pathParam("server", ""));
			if (serverName.isBlank()) {
				return jsonResponse(400, Map.of("error", "server name is required"), startedAt);
			}

			Map<String, BackendServerStatus> statuses = resolvedStatuses();
			BackendServerStatus status = statuses.get(serverName);
			if (status == null) {
				logger.debug("Không tìm thấy backend dashboard cho " + serverName);
				return jsonResponse(404, Map.of("error", "backend not found", "server", serverName), startedAt);
			}

			return jsonResponse(200, buildBackendDetail(status), startedAt);
		});
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
		summary.put("averageTps", round(dashboardStats.averageTps()));
		summary.put("averageCpu", round(dashboardStats.averageCpu()));
		summary.put("averageLatencyMillis", round(dashboardStats.averageLatency()));
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
		VelocityServerSelectorConfig.ServerDefinition definition = selectorConfig.server(status.serverName());
		String statusText = resolvedStatus(status);

		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("generatedAtEpochMillis", System.currentTimeMillis());
		detail.put("id", normalize(status.serverName()));
		detail.put("name", status.serverName());
		detail.put("displayName", status.serverDisplay());
		detail.put("accentColor", safe(status.serverAccentColor()));
		detail.put("hostName", hostName(status.serverName()));
		detail.put("status", statusText);
		detail.put("statusIcon", selectorConfig.icon(serverSelectorStatus(statusText)));
		detail.put("statusColor", selectorConfig.color(serverSelectorStatus(statusText)));
		detail.put("online", status.online());
		detail.put("lastHeartbeatEpochMillis", status.lastHeartbeatEpochMillis());
		detail.put("description", description(definition, statusText));
		detail.put("metrics", buildMetrics(stats));
		detail.put("stats", buildStats(stats));
		return detail;
	}

	private Map<String, Object> buildMetrics(BackendHeartbeatStats stats) {
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("onlinePlayers", stats == null ? 0 : Math.max(0, stats.onlinePlayers()));
		metrics.put("maxPlayers", stats == null ? 0 : Math.max(0, stats.maxPlayers()));
		metrics.put("playerUsagePercent", stats == null ? 0D : round(percent(stats.onlinePlayers(), stats.maxPlayers())));
		metrics.put("tps", stats == null ? 0D : round(stats.tps()));
		metrics.put("systemCpuUsagePercent", stats == null ? 0D : round(stats.systemCpuUsagePercent()));
		metrics.put("processCpuUsagePercent", stats == null ? 0D : round(stats.processCpuUsagePercent()));
		metrics.put("heartbeatLatencyMillis", stats == null ? 0L : Math.max(0L, stats.heartbeatLatencyMillis()));
		metrics.put("ramUsedBytes", stats == null ? 0L : Math.max(0L, stats.ramUsedBytes()));
		metrics.put("ramFreeBytes", stats == null ? 0L : Math.max(0L, stats.ramFreeBytes()));
		metrics.put("ramMaxBytes", stats == null ? 0L : Math.max(0L, stats.ramMaxBytes()));
		metrics.put("ramUsagePercent", stats == null ? 0D : round(percent(stats.ramUsedBytes(), stats.ramMaxBytes())));
		metrics.put("uptimeMillis", stats == null ? 0L : Math.max(0L, stats.uptimeMillis()));
		metrics.put("whitelistEnabled", stats != null && stats.whitelistEnabled());
		return metrics;
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
		payload.put("tps", round(stats.tps()));
		payload.put("onlinePlayers", Math.max(0, stats.onlinePlayers()));
		payload.put("maxPlayers", Math.max(0, stats.maxPlayers()));
		payload.put("motd", safe(stats.motd()));
		payload.put("whitelistEnabled", stats.whitelistEnabled());
		payload.put("systemCpuUsagePercent", round(stats.systemCpuUsagePercent()));
		payload.put("processCpuUsagePercent", round(stats.processCpuUsagePercent()));
		payload.put("ramUsedBytes", Math.max(0L, stats.ramUsedBytes()));
		payload.put("ramFreeBytes", Math.max(0L, stats.ramFreeBytes()));
		payload.put("ramMaxBytes", Math.max(0L, stats.ramMaxBytes()));
		payload.put("heartbeatLatencyMillis", Math.max(0L, stats.heartbeatLatencyMillis()));
		return payload;
	}

	private HttpResponse jsonResponse(int statusCode, Map<String, Object> payload, long startedAt) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("success", statusCode < 400);
		envelope.put("runtimeMillis", round((System.nanoTime() - startedAt) / 1_000_000D));
		if (statusCode < 400) {
			envelope.put("data", payload);
		} else {
			envelope.putAll(payload);
		}
		return HttpResponse.json(statusCode, toJson(envelope));
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

	private double round(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0D;
		}
		return Math.round(value * 100D) / 100D;
	}

	private String toJson(Object value) {
		if (value == null) {
			return "null";
		}

		if (value instanceof String stringValue) {
			return '"' + escapeJson(stringValue) + '"';
		}

		if (value instanceof Character characterValue) {
			return '"' + escapeJson(String.valueOf(characterValue)) + '"';
		}

		if (value instanceof Number numberValue) {
			double doubleValue = numberValue.doubleValue();
			if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
				return "null";
			}
			return String.valueOf(numberValue);
		}

		if (value instanceof Boolean booleanValue) {
			return String.valueOf(booleanValue);
		}

		if (value instanceof Enum<?> enumValue) {
			return '"' + escapeJson(enumValue.name()) + '"';
		}

		if (value instanceof Map<?, ?> mapValue) {
			StringBuilder out = new StringBuilder();
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append('"').append(':').append(toJson(entry.getValue()));
			}
			out.append('}');
			return out.toString();
		}

		if (value instanceof Iterable<?> iterableValue) {
			StringBuilder out = new StringBuilder();
			out.append('[');
			boolean first = true;
			for (Object item : iterableValue) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(toJson(item));
			}
			out.append(']');
			return out.toString();
		}

		return '"' + escapeJson(String.valueOf(value)) + '"';
	}

	private String escapeJson(String value) {
		StringBuilder out = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (character < 0x20) {
						out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
					} else {
						out.append(character);
					}
				}
			}
		}
		return out.toString();
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