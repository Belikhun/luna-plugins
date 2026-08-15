package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusRow;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfigCodec;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.serverselector.ServerSelectorOpenPayloadWriter;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityHeartbeatHttpEndpoints {
	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityBackendNameResolver nameResolver;
	private final VelocityServerSelectorConfig selectorConfig;
	private final AmqpMessagingConfig amqpMessagingConfig;
	private final RequestAuthorizer authorizer;
	private final boolean transportLoggingEnabled;
	private final Map<String, String> resolvedNameByAlias;

	public VelocityHeartbeatHttpEndpoints(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityBackendNameResolver nameResolver,
		VelocityServerSelectorConfig selectorConfig,
		AmqpMessagingConfig amqpMessagingConfig,
		RequestAuthorizer authorizer,
		boolean transportLoggingEnabled
	) {
		this.logger = logger.scope("HeartbeatHttp");
		this.statusRegistry = statusRegistry;
		this.nameResolver = nameResolver;
		this.selectorConfig = selectorConfig;
		this.amqpMessagingConfig = amqpMessagingConfig == null ? AmqpMessagingConfig.disabled() : amqpMessagingConfig.sanitize();
		this.authorizer = authorizer;
		this.transportLoggingEnabled = transportLoggingEnabled;
		this.resolvedNameByAlias = new ConcurrentHashMap<>();
	}

	public void register(Router router) {
		router.post("/heartbeat/{server}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối heartbeat do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			String serverName = request.pathParam("server", "").trim();
			if (serverName.isBlank()) {
				logger.warn("Heartbeat request thiếu server name trong path.");
				return HttpResponse.text(400, "server name is required");
			}

			Map<String, String> payload = HeartbeatFormCodec.decode(request.body());
			transportLog("[RX] POST /heartbeat/" + serverName + " raw=" + formatFormBody(request.body()));
			BackendHeartbeatStats incomingStats = HeartbeatFormCodec.decodeStats(payload);
			long receivedAt = System.currentTimeMillis();
			long clientSentAt = parseLong(payload.get("clientSentEpochMillis"), 0L);
			long latencyMs = clientSentAt <= 0L ? 0L : Math.max(0L, receivedAt - clientSentAt);
			BackendHeartbeatStats stats = withLatency(incomingStats, latencyMs);
			boolean online = ConfigValues.booleanValue(payload, "online", true);
			String resolvedServerName = nameResolver.resolve(serverName, request.headers(), stats.serverPort());
			rememberResolvedName(serverName, resolvedServerName);
			VelocityServerSelectorConfig.ServerDefinition definition = selectorConfig.server(resolvedServerName);
			BackendMetadata backendMetadata = resolvedBackendMetadata(resolveBackendMetadata(resolvedServerName, payload, definition), resolvedServerName);
			long sinceRevision = parseSinceRevision(request.queryParam("since", "-1"));
			String sinceEpoch = request.queryParam("epoch", "").trim();
			BackendServerStatus status = online
				? statusRegistry.upsert(backendMetadata, stats, System.currentTimeMillis())
				: statusRegistry.markOffline(backendMetadata, stats, System.currentTimeMillis());
			logger.debug("Heartbeat endpoint: source=" + serverName
				+ " resolved=" + resolvedServerName
				+ " online=" + status.online()
				+ " players=" + stats.onlinePlayers() + "/" + stats.maxPlayers()
				+ " latency=" + latencyMs + "ms"
				+ " port=" + stats.serverPort());

			// a cursor from a previous proxy process counts for nothing: the
			// revisions it refers to were minted by a registry that is gone
			boolean staleEpoch = !sinceEpoch.isEmpty() && !sinceEpoch.equals(statusRegistry.epoch());
			boolean fullSync = sinceRevision < 0 || staleEpoch;
			if (staleEpoch) {
				logger.debug("Backend " + resolvedServerName + " gửi epoch cũ (" + sinceEpoch + "), trả full sync.");
			}

			// read the revision first: a row written between collecting the rows and
			// reading the counter would otherwise be behind the cursor we hand back,
			// and the backend would never ask for it again
			long responseRevision = statusRegistry.currentRevision();
			List<BackendStatusRow> responseRows = fullSync
				? buildInitialFullRows()
				: statusRegistry.rowsSince(sinceRevision);
			byte[] body = HeartbeatFormCodec.encodeRows(
				responseRows,
				responseRevision,
				statusRegistry.epoch(),
				fullSync,
				resolvedServerName,
				backendMetadata
			);
			transportLog("[TX] POST /heartbeat/" + serverName + " status=200 raw=" + formatFormBody(body));
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/heartbeat/servers", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /heartbeat/servers do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}
			logger.debug("Yêu cầu snapshot toàn bộ backend statuses.");

			byte[] body = HeartbeatFormCodec.encodeRows(
				statusRegistry.allRows(),
				statusRegistry.currentRevision(),
				statusRegistry.epoch(),
				true,
				null,
				null
			);
			transportLog("[TX] GET /heartbeat/servers status=200 raw=" + formatFormBody(body));
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/heartbeat/servers/{server}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /heartbeat/servers/{server} do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			String serverName = request.pathParam("server", "").trim();
			if (serverName.isBlank()) {
				logger.warn("Truy vấn /heartbeat/servers/{server} thiếu server name.");
				return HttpResponse.text(400, "server name is required");
			}
			logger.debug("Yêu cầu status cho backend=" + serverName);

			BackendStatusRow row = statusRegistry.row(serverName);
			byte[] body = HeartbeatFormCodec.encodeRows(
				row == null ? List.of() : List.of(row),
				statusRegistry.currentRevision(),
				statusRegistry.epoch(),
				true,
				serverName,
				null
			);
			transportLog("[TX] GET /heartbeat/servers/" + serverName + " status=200 raw=" + formatFormBody(body));
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/server-selector-config", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /server-selector-config do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			PluginMessageWriter writer = PluginMessageWriter.create();
			ServerSelectorOpenPayloadWriter.write(writer, selectorConfig);
			byte[] body = writer.toByteArray();
			transportLog("[TX] GET /server-selector-config status=200 raw=" + formatBinaryBody(body));
			return HttpResponse.bytes(200, body, "application/octet-stream");
		});

		router.get("/messaging-config", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /messaging-config do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			byte[] body = AmqpMessagingConfigCodec.encode(amqpMessagingConfig);
			transportLog("[TX] GET /messaging-config status=200 raw=" + formatFormBody(body));
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/messaging-config/{server}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /messaging-config/{server} do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			String requestedServer = request.pathParam("server", "").trim();
			String resolvedServer = resolveAlias(requestedServer);
			AmqpMessagingConfig scoped = new AmqpMessagingConfig(
				amqpMessagingConfig.enabled(),
				amqpMessagingConfig.uri(),
				amqpMessagingConfig.exchange(),
				amqpMessagingConfig.proxyQueue(),
				amqpMessagingConfig.backendQueuePrefix(),
				resolvedServer,
				amqpMessagingConfig.connectionTimeoutMillis(),
				amqpMessagingConfig.requestedHeartbeatSeconds()
			).sanitize();
			byte[] body = AmqpMessagingConfigCodec.encode(scoped);
			transportLog("[TX] GET /messaging-config/" + requestedServer + " status=200 raw=" + formatFormBody(body));
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});
	}

	private BackendMetadata resolveBackendMetadata(
		String resolvedServerName,
		Map<String, String> payload,
		VelocityServerSelectorConfig.ServerDefinition definition
	) {
		BackendMetadata configuredMetadata = selectorConfig.backendMetadata(resolvedServerName);
		String definitionDisplay = definition != null ? definition.displayName() : configuredMetadata.displayName();
		String definitionAccent = definition != null && definition.accentColor() != null && !definition.accentColor().isBlank()
			? definition.accentColor()
			: configuredMetadata.accentColor();
		String definitionServerName = configuredMetadata.serverName();
		String displayName = ConfigValues.string(payload, "server_display", definitionDisplay);
		String accentColor = ConfigValues.string(payload, "server_accent_color", definitionAccent);
		String serverName = ConfigValues.string(payload, "server_name", definitionServerName);
		return new BackendMetadata(resolvedServerName, displayName, accentColor, serverName).sanitize();
	}

	private BackendMetadata resolvedBackendMetadata(BackendMetadata metadata, String backendName) {
		BackendMetadata sanitized = metadata == null ? new BackendMetadata(backendName, "", "", "") : metadata.sanitize();
		if (sanitized.serverName() != null && !sanitized.serverName().isBlank()) {
			return sanitized;
		}

		String host = nameResolver.serverHost(backendName);
		return new BackendMetadata(sanitized.name(), sanitized.displayName(), sanitized.accentColor(), host).sanitize();
	}

	private void rememberResolvedName(String sourceName, String resolvedServerName) {
		String normalizedSource = normalize(sourceName);
		String normalizedResolved = normalize(resolvedServerName);
		if (!normalizedSource.isBlank() && !normalizedResolved.isBlank()) {
			resolvedNameByAlias.put(normalizedSource, resolvedServerName.trim());
		}
		if (!normalizedResolved.isBlank()) {
			resolvedNameByAlias.put(normalizedResolved, resolvedServerName.trim());
		}
	}

	private String resolveAlias(String requestedServer) {
		String normalizedRequested = normalize(decodePathSegment(requestedServer));
		if (normalizedRequested.isBlank()) {
			return "";
		}

		String resolved = resolvedNameByAlias.get(normalizedRequested);
		if (resolved != null && !resolved.isBlank()) {
			return resolved;
		}

		BackendServerStatus status = statusRegistry.status(normalizedRequested).orElse(null);
		if (status != null && status.serverName() != null && !status.serverName().isBlank()) {
			rememberResolvedName(requestedServer, status.serverName());
			return status.serverName();
		}

		return decodePathSegment(requestedServer).trim();
	}

	private String decodePathSegment(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
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

	/**
	 * The body of a full sync: every configured server, whether or not it has ever
	 * checked in, so a backend can draw the whole selector before the rest of the
	 * cluster has reported. Servers that never heartbeat are synthesised as
	 * offline rows at revision 0 — they are not written into the registry, which
	 * only ever holds what a backend actually said.
	 */
	private List<BackendStatusRow> buildInitialFullRows() {
		Map<String, BackendStatusRow> merged = new LinkedHashMap<>();

		for (VelocityServerSelectorConfig.ServerDefinition definition : selectorConfig.servers().values()) {
			String key = normalize(definition.backendName());
			BackendStatusRow currentRow = statusRegistry.row(definition.backendName());
			BackendMetadata definitionMetadata = resolvedBackendMetadata(selectorConfig.backendMetadata(definition.backendName()), definition.backendName());
			if (currentRow == null) {
				merged.put(key, new BackendStatusRow(new BackendServerStatus(
					definitionMetadata.name(),
					definitionMetadata.displayName(),
					definitionMetadata.accentColor(),
					false,
					0L,
					emptyStats()
				), 0L));
				continue;
			}

			BackendServerStatus currentStatus = currentRow.status();
			BackendMetadata currentMetadata = currentStatus.metadata();
			merged.put(key, new BackendStatusRow(new BackendServerStatus(
				currentMetadata.name(),
				definitionMetadata.displayName(),
				definitionMetadata.accentColor().isBlank() ? currentMetadata.accentColor() : definitionMetadata.accentColor(),
				currentStatus.online(),
				currentStatus.lastHeartbeatEpochMillis(),
				currentStatus.stats()
			), currentRow.revision()));
		}

		for (BackendStatusRow row : statusRegistry.allRows()) {
			merged.putIfAbsent(normalize(row.serverName()), row);
		}

		return new ArrayList<>(merged.values());
	}

	private BackendHeartbeatStats emptyStats() {
		return new BackendHeartbeatStats("unknown", "unknown", 0, 0L, 0D, 0, 0, "", false, 0D, 0D, 0L, 0L, 0L, 0L);
	}

	private BackendHeartbeatStats withLatency(BackendHeartbeatStats stats, long latencyMs) {
		return stats == null ? emptyStats() : stats.withLatency(latencyMs);
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


	private void transportLog(String message) {
		if (!transportLoggingEnabled) {
			return;
		}
		logger.audit("Heartbeat transport: " + message);
	}

	private String formatFormBody(byte[] body) {
		if (body == null || body.length == 0) {
			return "<empty>";
		}
		return new String(body, StandardCharsets.UTF_8);
	}

	private String formatBinaryBody(byte[] body) {
		if (body == null || body.length == 0) {
			return "<empty>";
		}
		return "base64=" + Base64.getEncoder().encodeToString(body);
	}

}
