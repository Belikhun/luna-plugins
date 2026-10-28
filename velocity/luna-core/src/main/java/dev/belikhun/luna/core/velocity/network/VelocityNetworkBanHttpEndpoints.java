package dev.belikhun.luna.core.velocity.network;

import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Network-level bans, the console's management surface: list what the proxy
 * refuses, add a ban, lift one, for accounts ({@code /network/bans}) and for
 * addresses ({@code /network/ip-bans}). Every mutation goes through
 * {@link NetworkModeration}, so it also disconnects whoever it hits that is
 * online and lands in the moderation log.
 *
 * Bodies are form-encoded: {@code action} (add or remove), the target
 * ({@code player} or {@code ip}; an ip ban also takes a player name and bans
 * their address), {@code reason}, {@code actor} and {@code expiresAt} (epoch
 * millis, 0 or absent for permanent).
 */
public final class VelocityNetworkBanHttpEndpoints {
	private final LunaLogger logger;
	private final NetworkModeration moderation;
	private final RequestAuthorizer authorizer;

	public VelocityNetworkBanHttpEndpoints(
		LunaLogger logger,
		NetworkModeration moderation,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("NetworkBanHttp");
		this.moderation = moderation;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/network/bans", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /network/bans do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			if (!moderation.playerBans().available()) {
				return LunaJson.error(503, "network ban database is not available");
			}

			List<Map<String, Object>> rows = new ArrayList<>();

			for (VelocityNetworkPlayerBanStore.Entry entry : moderation.playerBans().list()) {
				rows.add(NetworkModeration.playerBanRow(entry));
			}

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("total", rows.size());
			payload.put("bans", rows);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.post("/network/bans", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối POST /network/bans do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim();
			String player = body.getOrDefault("player", "").trim();
			String reason = body.getOrDefault("reason", "").trim();
			String actor = body.getOrDefault("actor", "").trim();
			long expiresAt = parseLong(body.getOrDefault("expiresAt", ""));

			if (player.isBlank()) {
				return LunaJson.error(400, "player is required");
			}

			if ("add".equals(action)) {
				return respond(moderation.ban(player, reason, actor, expiresAt), startedAt);
			}

			if ("remove".equals(action)) {
				return respond(moderation.unban(player, reason, actor), startedAt);
			}

			return LunaJson.error(400, "action must be add or remove");
		});

		router.get("/network/ip-bans", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /network/ip-bans do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			if (!moderation.ipBans().available()) {
				return LunaJson.error(503, "network ban database is not available");
			}

			List<Map<String, Object>> rows = new ArrayList<>();

			for (VelocityNetworkBanStore.Entry entry : moderation.ipBans().list()) {
				rows.add(NetworkModeration.ipBanRow(entry));
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
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim();
			String target = body.getOrDefault("ip", "").trim();
			String reason = body.getOrDefault("reason", "").trim();
			String actor = body.getOrDefault("actor", "").trim();
			long expiresAt = parseLong(body.getOrDefault("expiresAt", ""));

			if (target.isBlank()) {
				target = body.getOrDefault("player", "").trim();
			}

			if (target.isBlank()) {
				return LunaJson.error(400, "a valid bare IP address is required");
			}

			if ("add".equals(action)) {
				return respond(moderation.banIp(target, reason, actor, expiresAt), startedAt);
			}

			if ("remove".equals(action)) {
				return respond(moderation.unbanIp(target, reason, actor), startedAt);
			}

			return LunaJson.error(400, "action must be add or remove");
		});

		router.post("/network/kick", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối POST /network/kick do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String player = body.getOrDefault("player", "").trim();

			if (player.isBlank()) {
				return LunaJson.error(400, "player is required");
			}

			return respond(
				moderation.kick(player, body.getOrDefault("reason", ""), body.getOrDefault("actor", "")),
				startedAt
			);
		});
	}

	private HttpResponse respond(NetworkModeration.Result result, long startedAt) {
		if (!result.ok()) {
			return LunaJson.error(result.status(), result.error());
		}

		return LunaJson.envelope(200, result.data(), startedAt);
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
