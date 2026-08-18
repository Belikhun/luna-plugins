package dev.belikhun.luna.core.velocity.network;

import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.players.VelocityPlayerRecordStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Network-level IP bans, the console's management surface: list what the proxy
 * refuses, add a ban, lift one. Every mutation lands in the moderation log so
 * the network's history stays complete.
 */
public final class VelocityNetworkBanHttpEndpoints {
	private final LunaLogger logger;
	private final VelocityNetworkBanStore banStore;
	private final VelocityPlayerRecordStore recordStore;
	private final RequestAuthorizer authorizer;

	public VelocityNetworkBanHttpEndpoints(
		LunaLogger logger,
		VelocityNetworkBanStore banStore,
		VelocityPlayerRecordStore recordStore,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("NetworkBanHttp");
		this.banStore = banStore;
		this.recordStore = recordStore;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/network/ip-bans", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /network/ip-bans do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			if (!banStore.available()) {
				return LunaJson.error(503, "network ban database is not available");
			}

			List<Map<String, Object>> rows = new ArrayList<>();
			for (VelocityNetworkBanStore.Entry entry : banStore.list()) {
				rows.add(toRow(entry));
			}

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("total", rows.size());
			payload.put("bans", rows);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.post("/network/ip-bans", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối POST /network/ip-bans do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			if (!banStore.available()) {
				return LunaJson.error(503, "network ban database is not available");
			}

			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim();
			String ip = body.getOrDefault("ip", "").trim();
			String reason = body.getOrDefault("reason", "").trim();
			String actor = body.getOrDefault("actor", "").trim();
			long expiresAt = parseLong(body.getOrDefault("expiresAt", ""));

			if (ip.isBlank() || !VelocityNetworkBanStore.validIp(ip)) {
				return LunaJson.error(400, "a valid bare IP address is required");
			}

			if ("add".equals(action)) {
				VelocityNetworkBanStore.Entry entry = banStore.add(ip, reason, actor, expiresAt);
				recordStore.recordModeration("", ip, "network-ban-ip", actor, reason, "proxy", "");

				Map<String, Object> payload = toRow(entry);
				return LunaJson.envelope(200, payload, startedAt);
			}

			if ("remove".equals(action)) {
				boolean existed = banStore.remove(ip);

				if (existed) {
					recordStore.recordModeration("", ip, "network-pardon-ip", actor, reason, "proxy", "");
				}

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("ip", ip);
				payload.put("removed", existed);
				return LunaJson.envelope(200, payload, startedAt);
			}

			return LunaJson.error(400, "action must be add or remove");
		});
	}

	private Map<String, Object> toRow(VelocityNetworkBanStore.Entry entry) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("ip", entry.ip);
		row.put("reason", entry.reason);
		row.put("actor", entry.actor);
		row.put("createdAtEpochMillis", entry.createdAt);
		row.put("expiresAtEpochMillis", entry.expiresAt);
		row.put("hits", entry.hits);
		row.put("lastHitAtEpochMillis", entry.lastHitAt);
		return row;
	}

	private long parseLong(String raw) {
		if (raw == null || raw.isBlank()) {
			return 0L;
		}

		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}
}
