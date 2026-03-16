package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.serverselector.ServerSelectorOpenPayloadWriter;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class VelocityHeartbeatHttpEndpoints {
	private static final String TOKEN_HEADER = "X-Luna-Forwarding-Secret";

	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityBackendNameResolver nameResolver;
	private final VelocityServerSelectorConfig selectorConfig;
	private final String forwardingSecret;

	public VelocityHeartbeatHttpEndpoints(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityBackendNameResolver nameResolver,
		VelocityServerSelectorConfig selectorConfig,
		String forwardingSecret
	) {
		this.logger = logger.scope("HeartbeatHttp");
		this.statusRegistry = statusRegistry;
		this.nameResolver = nameResolver;
		this.selectorConfig = selectorConfig;
		this.forwardingSecret = forwardingSecret == null ? "" : forwardingSecret;
	}

	public void register(Router router) {
		router.post("/heartbeat/{server}", request -> {
			if (!isAuthorized(request.headers())) {
				logger.warn("Từ chối heartbeat do sai token hoặc thiếu token.");
				return unauthorized();
			}

			String serverName = request.pathParam("server", "").trim();
			if (serverName.isBlank()) {
				logger.warn("Heartbeat request thiếu server name trong path.");
				return HttpResponse.text(400, "server name is required");
			}

			Map<String, String> payload = HeartbeatFormCodec.decode(request.body());
			BackendHeartbeatStats incomingStats = HeartbeatFormCodec.decodeStats(payload);
			long receivedAt = System.currentTimeMillis();
			long clientSentAt = parseLong(payload.get("clientSentEpochMillis"), 0L);
			long latencyMs = clientSentAt <= 0L ? 0L : Math.max(0L, receivedAt - clientSentAt);
			BackendHeartbeatStats stats = withLatency(incomingStats, latencyMs);
			boolean online = ConfigValues.booleanValue(payload, "online", true);
			String resolvedServerName = nameResolver.resolve(serverName, request.headers(), stats.serverPort());
			VelocityServerSelectorConfig.ServerDefinition definition = selectorConfig.server(resolvedServerName);
			String serverDisplay = ConfigValues.string(payload, "server_display", definition != null ? definition.displayName() : resolvedServerName);
			String serverAccentColor = ConfigValues.string(payload, "server_accent_color", definition != null ? definition.accentColor() : "");
			long sinceRevision = parseSinceRevision(request.queryParam("since", "-1"));
			BackendServerStatus status = online
				? statusRegistry.upsert(resolvedServerName, serverDisplay, serverAccentColor, stats, System.currentTimeMillis())
				: statusRegistry.markOffline(resolvedServerName, serverDisplay, serverAccentColor, stats, System.currentTimeMillis());
			logger.debug("Heartbeat endpoint: source=" + serverName
				+ " resolved=" + resolvedServerName
				+ " online=" + status.online()
				+ " players=" + stats.onlinePlayers() + "/" + stats.maxPlayers()
				+ " latency=" + latencyMs + "ms"
				+ " port=" + stats.serverPort());

			Map<String, BackendServerStatus> responseRows = sinceRevision < 0
				? buildInitialFullRows()
				: statusRegistry.deltaSince(sinceRevision);
			boolean fullSync = sinceRevision < 0;
			byte[] body = HeartbeatFormCodec.encodeSnapshot(responseRows, statusRegistry.currentRevision(), resolvedServerName, fullSync);
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/heartbeat/servers", request -> {
			if (!isAuthorized(request.headers())) {
				logger.warn("Từ chối truy vấn /heartbeat/servers do sai token hoặc thiếu token.");
				return unauthorized();
			}
			logger.debug("Yêu cầu snapshot toàn bộ backend statuses.");

			byte[] body = HeartbeatFormCodec.encodeSnapshot(statusRegistry.snapshot(), statusRegistry.currentRevision(), null, true);
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/heartbeat/servers/{server}", request -> {
			if (!isAuthorized(request.headers())) {
				logger.warn("Từ chối truy vấn /heartbeat/servers/{server} do sai token hoặc thiếu token.");
				return unauthorized();
			}

			String serverName = request.pathParam("server", "").trim();
			if (serverName.isBlank()) {
				logger.warn("Truy vấn /heartbeat/servers/{server} thiếu server name.");
				return HttpResponse.text(400, "server name is required");
			}
			logger.debug("Yêu cầu status cho backend=" + serverName);

			Map<String, BackendServerStatus> single = new LinkedHashMap<>();
			statusRegistry.status(serverName).ifPresent(status -> single.put(serverName.toLowerCase(), status));
			byte[] body = HeartbeatFormCodec.encodeSnapshot(single, statusRegistry.currentRevision(), serverName, true);
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/server-selector-config", request -> {
			if (!isAuthorized(request.headers())) {
				logger.warn("Từ chối truy vấn /server-selector-config do sai token hoặc thiếu token.");
				return unauthorized();
			}

			PluginMessageWriter writer = PluginMessageWriter.create();
			ServerSelectorOpenPayloadWriter.write(writer, selectorConfig);
			return HttpResponse.bytes(200, writer.toByteArray(), "application/octet-stream");
		});
	}

	private long parseSinceRevision(String raw) {
		if (raw == null || raw.isBlank()) {
			return -1L;
		}

		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException ignored) {
			return -1L;
		}
	}

	private Map<String, BackendServerStatus> buildInitialFullRows() {
		Map<String, BackendServerStatus> merged = new LinkedHashMap<>();
		Map<String, BackendServerStatus> current = statusRegistry.snapshot();

		for (VelocityServerSelectorConfig.ServerDefinition definition : selectorConfig.servers().values()) {
			String key = normalize(definition.backendName());
			BackendServerStatus currentStatus = current.get(key);
			if (currentStatus == null) {
				merged.put(key, new BackendServerStatus(
					definition.backendName(),
					definition.displayName(),
					definition.accentColor(),
					false,
					0L,
					emptyStats()
				));
				continue;
			}

			merged.put(key, new BackendServerStatus(
				currentStatus.serverName(),
				definition.displayName(),
				definition.accentColor().isBlank() ? currentStatus.serverAccentColor() : definition.accentColor(),
				currentStatus.online(),
				currentStatus.lastHeartbeatEpochMillis(),
				currentStatus.stats()
			));
		}

		for (Map.Entry<String, BackendServerStatus> entry : current.entrySet()) {
			merged.putIfAbsent(entry.getKey(), entry.getValue());
		}

		return merged;
	}

	private BackendHeartbeatStats emptyStats() {
		return new BackendHeartbeatStats("unknown", "unknown", 0, 0L, 0D, 0, 0, "", false, 0D, 0D, 0L, 0L, 0L, 0L);
	}

	private BackendHeartbeatStats withLatency(BackendHeartbeatStats stats, long latencyMs) {
		if (stats == null) {
			return emptyStats();
		}

		return new BackendHeartbeatStats(
			stats.software(),
			stats.version(),
			stats.serverPort(),
			stats.uptimeMillis(),
			stats.tps(),
			stats.onlinePlayers(),
			stats.maxPlayers(),
			stats.motd(),
			stats.whitelistEnabled(),
			stats.systemCpuUsagePercent(),
			stats.processCpuUsagePercent(),
			stats.ramUsedBytes(),
			stats.ramFreeBytes(),
			stats.ramMaxBytes(),
			Math.max(0L, latencyMs)
		);
	}

	private long parseLong(String value, long fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}

		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private boolean isAuthorized(Map<String, java.util.List<String>> headers) {
		if (forwardingSecret.isBlank()) {
			return false;
		}

		if (headers == null || headers.isEmpty()) {
			return false;
		}

		for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
			if (!TOKEN_HEADER.equalsIgnoreCase(entry.getKey())) {
				continue;
			}

			for (String value : entry.getValue()) {
				if (forwardingSecret.equals(value)) {
					return true;
				}
			}
		}

		return false;
	}

	private HttpResponse unauthorized() {
		return HttpResponse.bytes(401, "unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
	}

}
