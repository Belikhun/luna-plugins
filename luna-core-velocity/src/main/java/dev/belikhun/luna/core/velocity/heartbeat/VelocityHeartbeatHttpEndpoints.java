package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VelocityHeartbeatHttpEndpoints {
	private static final String TOKEN_HEADER = "X-Luna-Forwarding-Secret";

	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityBackendNameResolver nameResolver;
	private final String forwardingSecret;

	public VelocityHeartbeatHttpEndpoints(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityBackendNameResolver nameResolver,
		String forwardingSecret
	) {
		this.logger = logger.scope("HeartbeatHttp");
		this.statusRegistry = statusRegistry;
		this.nameResolver = nameResolver;
		this.forwardingSecret = forwardingSecret == null ? "" : forwardingSecret;
	}

	public void register(Router router) {
		router.post("/heartbeat/{server}", request -> {
			if (!isAuthorized(request.headers())) {
				return unauthorized();
			}

			String serverName = request.pathParam("server", "").trim();
			if (serverName.isBlank()) {
				return HttpResponse.text(400, "server name is required");
			}

			Map<String, String> payload = HeartbeatFormCodec.decode(request.body());
			BackendHeartbeatStats stats = HeartbeatFormCodec.decodeStats(payload);
			String resolvedServerName = nameResolver.resolve(serverName, request.headers(), stats.serverPort());
			BackendServerStatus status = statusRegistry.upsert(resolvedServerName, stats, System.currentTimeMillis());
			logger.audit("Heartbeat từ backend=" + status.serverName() + " players=" + stats.onlinePlayers() + "/" + stats.maxPlayers() + " tps=" + stats.tps());

			byte[] body = HeartbeatFormCodec.encodeSnapshot(statusRegistry.snapshot());
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/heartbeat/servers", request -> {
			if (!isAuthorized(request.headers())) {
				return unauthorized();
			}

			byte[] body = HeartbeatFormCodec.encodeSnapshot(statusRegistry.snapshot());
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});

		router.get("/heartbeat/servers/{server}", request -> {
			if (!isAuthorized(request.headers())) {
				return unauthorized();
			}

			String serverName = request.pathParam("server", "").trim();
			if (serverName.isBlank()) {
				return HttpResponse.text(400, "server name is required");
			}

			Map<String, BackendServerStatus> single = new LinkedHashMap<>();
			statusRegistry.status(serverName).ifPresent(status -> single.put(serverName.toLowerCase(), status));
			byte[] body = HeartbeatFormCodec.encodeSnapshot(single);
			return HttpResponse.bytes(200, body, "application/x-www-form-urlencoded; charset=utf-8");
		});
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
