package dev.belikhun.luna.core.velocity.players;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.profile.LuckPermsUserInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The persisted player directory: every player the proxy has ever seen, with
 * profile, play history, chat/command log and moderation log.
 *
 * These routes must be registered <em>before</em> {@link VelocityPlayerHttpEndpoints},
 * because the router matches in registration order and {@code /players/registered}
 * would otherwise be swallowed by {@code /players/{player}}.
 */
public final class VelocityPlayerDirectoryHttpEndpoints {
	private static final int DEFAULT_PAGE_LIMIT = 25;
	private static final int MAX_PAGE_LIMIT = 200;

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityPlayerSessionRegistry sessionRegistry;
	private final VelocityPlayerRecordStore recordStore;
	private final LuckPermsService luckPermsService;
	private final RequestAuthorizer authorizer;

	public VelocityPlayerDirectoryHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityPlayerSessionRegistry sessionRegistry,
		VelocityPlayerRecordStore recordStore,
		LuckPermsService luckPermsService,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("PlayerDirectoryHttp");
		this.proxyServer = proxyServer;
		this.sessionRegistry = sessionRegistry;
		this.recordStore = recordStore;
		this.luckPermsService = luckPermsService;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/players/registered", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /players/registered do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			if (!recordStore.available()) {
				return LunaJson.error(503, "player directory database is not available");
			}

			String search = request.queryParam("search", "");
			String sort = request.queryParam("sort", "");
			boolean ascending = "asc".equalsIgnoreCase(request.queryParam("dir", ""));
			int limit = clamp(parseInt(request.queryParam("limit", ""), DEFAULT_PAGE_LIMIT), 1, MAX_PAGE_LIMIT);
			int offset = Math.max(0, parseInt(request.queryParam("offset", ""), 0));

			List<Map<String, Object>> rows = recordStore.listProfiles(search, sort, ascending, offset, limit);
			long total = recordStore.countProfiles(search);

			List<Map<String, Object>> players = new ArrayList<>(rows.size());
			for (Map<String, Object> row : rows) {
				players.add(buildProfileRow(row));
			}

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("total", total);
			payload.put("offset", offset);
			payload.put("limit", limit);
			payload.put("players", players);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.get("/players/registered/{player}", request -> {
			return withProfile(request, "profile", (profile, startedAt) -> {
				Map<String, Object> payload = buildProfileRow(profile);
				String uuid = stringOf(profile.get("uuid"));

				payload.put("skinTexture", nullableString(profile.get("skin_texture")));
				payload.put("skinSignature", nullableString(profile.get("skin_signature")));

				List<Map<String, Object>> perServer = new ArrayList<>();
				for (Map<String, Object> row : recordStore.playtimeByServer(uuid)) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("server", stringOf(row.get("server")));
					entry.put("playMillis", longOf(row.get("play_millis")));
					entry.put("stints", longOf(row.get("stints")));
					perServer.add(entry);
				}
				payload.put("playtimeByServer", perServer);

				payload.put("sessionTotal", recordStore.sessionCount(uuid));
				payload.put("chatTotal", recordStore.chatCount(uuid, "chat"));
				payload.put("commandTotal", recordStore.chatCount(uuid, "command"));
				payload.put("moderationTotal", recordStore.moderationCount(uuid));
				payload.put("permissions", permissionsSummary(uuid));

				return LunaJson.envelope(200, payload, startedAt);
			});
		});

		router.get("/players/registered/{player}/sessions", request -> {
			return withProfile(request, "sessions", (profile, startedAt) -> {
				String uuid = stringOf(profile.get("uuid"));
				int limit = clamp(parseInt(request.queryParam("limit", ""), DEFAULT_PAGE_LIMIT), 1, MAX_PAGE_LIMIT);
				int offset = Math.max(0, parseInt(request.queryParam("offset", ""), 0));

				List<Map<String, Object>> sessions = new ArrayList<>();
				for (Map<String, Object> row : recordStore.sessions(uuid, offset, limit)) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("id", longOf(row.get("id")));
					entry.put("server", stringOf(row.get("server")));
					entry.put("connectedAtEpochMillis", longOf(row.get("connected_at")));
					entry.put("disconnectedAtEpochMillis", row.get("disconnected_at") == null ? 0L : longOf(row.get("disconnected_at")));
					entry.put("durationMillis", longOf(row.get("duration_millis")));
					entry.put("open", row.get("disconnected_at") == null);
					sessions.add(entry);
				}

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("total", recordStore.sessionCount(uuid));
				payload.put("offset", offset);
				payload.put("limit", limit);
				payload.put("sessions", sessions);
				return LunaJson.envelope(200, payload, startedAt);
			});
		});

		router.get("/players/registered/{player}/chat", request -> {
			return withProfile(request, "chat", (profile, startedAt) -> {
				String uuid = stringOf(profile.get("uuid"));
				String type = normalizeType(request.queryParam("type", ""));
				int limit = clamp(parseInt(request.queryParam("limit", ""), DEFAULT_PAGE_LIMIT), 1, MAX_PAGE_LIMIT);
				int offset = Math.max(0, parseInt(request.queryParam("offset", ""), 0));

				List<Map<String, Object>> entries = new ArrayList<>();
				for (Map<String, Object> row : recordStore.chat(uuid, type, offset, limit)) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("id", longOf(row.get("id")));
					entry.put("server", stringOf(row.get("server")));
					entry.put("type", stringOf(row.get("type")));
					entry.put("content", stringOf(row.get("content")));
					entry.put("atEpochMillis", longOf(row.get("at")));
					entries.add(entry);
				}

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("total", recordStore.chatCount(uuid, type));
				payload.put("offset", offset);
				payload.put("limit", limit);
				payload.put("entries", entries);
				return LunaJson.envelope(200, payload, startedAt);
			});
		});

		router.get("/players/registered/{player}/moderation", request -> {
			return withProfile(request, "moderation", (profile, startedAt) -> {
				String uuid = stringOf(profile.get("uuid"));
				int limit = clamp(parseInt(request.queryParam("limit", ""), DEFAULT_PAGE_LIMIT), 1, MAX_PAGE_LIMIT);
				int offset = Math.max(0, parseInt(request.queryParam("offset", ""), 0));

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("total", recordStore.moderationCount(uuid));
				payload.put("offset", offset);
				payload.put("limit", limit);
				payload.put("entries", moderationEntries(uuid, offset, limit));
				return LunaJson.envelope(200, payload, startedAt);
			});
		});

		// The daemon reports every moderation action it performs (ban, whitelist,
		// op, …) here, so the player's moderation history is complete even for
		// actions LunaCore itself never sees.
		router.post("/moderation/log", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối POST /moderation/log do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			if (!recordStore.available()) {
				return LunaJson.error(503, "player directory database is not available");
			}

			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim();
			String targetName = body.getOrDefault("targetName", "").trim();
			String targetUuid = body.getOrDefault("targetUuid", "").trim();

			if (action.isBlank()) {
				return LunaJson.error(400, "action is required");
			}

			if (targetUuid.isBlank() && targetName.isBlank()) {
				return LunaJson.error(400, "targetName or targetUuid is required");
			}

			// Resolve whichever half of the identity is missing from the directory.
			if (targetUuid.isBlank()) {
				targetUuid = recordStore.findProfile(targetName)
					.map(profile -> stringOf(profile.get("uuid")))
					.orElse("");
			} else if (targetName.isBlank()) {
				targetName = recordStore.findProfile(targetUuid)
					.map(profile -> stringOf(profile.get("username")))
					.orElse("");
			}

			recordStore.recordModeration(
				targetUuid,
				targetName,
				action,
				body.getOrDefault("actor", ""),
				body.getOrDefault("reason", ""),
				body.getOrDefault("server", ""),
				body.getOrDefault("details", "")
			);

			logger.audit("Ghi moderation log: action=" + action + ", target=" + targetName
				+ (targetUuid.isBlank() ? "" : " (" + targetUuid + ")"));

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("action", action);
			payload.put("targetUuid", targetUuid);
			payload.put("targetName", targetName);
			return LunaJson.envelope(200, payload, startedAt);
		});
	}

	/** Shared shape for per-player routes: authorize, resolve the profile, act. */
	private dev.belikhun.luna.core.api.http.HttpResponse withProfile(
		dev.belikhun.luna.core.api.http.HttpRequest request,
		String what,
		ProfileAction handler
	) {
		if (!authorizer.authorized(request)) {
			logger.warn("Từ chối truy vấn /players/registered/... (" + what + ") do sai token hoặc thiếu token.");
			return authorizer.unauthorized();
		}

		long startedAt = System.nanoTime();

		if (!recordStore.available()) {
			return LunaJson.error(503, "player directory database is not available");
		}

		String reference = request.pathParam("player", "").trim();
		Optional<Map<String, Object>> profile = recordStore.findProfile(reference);

		if (profile.isEmpty()) {
			return LunaJson.error(404, "player not found in directory: " + reference);
		}

		return handler.apply(profile.get(), startedAt);
	}

	@FunctionalInterface
	private interface ProfileAction {
		dev.belikhun.luna.core.api.http.HttpResponse apply(Map<String, Object> profile, long startedAt);
	}

	/** One directory row: the persisted profile merged with live session state. */
	private Map<String, Object> buildProfileRow(Map<String, Object> profile) {
		String uuidText = stringOf(profile.get("uuid"));

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("uuid", uuidText);
		row.put("username", stringOf(profile.get("username")));
		row.put("firstSeenAtEpochMillis", longOf(profile.get("first_seen_at")));
		row.put("lastSeenAtEpochMillis", longOf(profile.get("last_seen_at")));
		row.put("lastServer", stringOf(profile.get("last_server")));
		row.put("lastAddress", stringOf(profile.get("last_address")));
		row.put("lastClientVersion", stringOf(profile.get("last_client_version")));
		row.put("onlineMode", longOf(profile.get("online_mode")) != 0L);
		row.put("sessionCount", longOf(profile.get("session_count")));
		row.put("hasSkin", profile.get("skin_texture") != null);

		long totalPlayMillis = longOf(profile.get("total_play_millis"));
		Optional<Player> online = resolveOnline(uuidText);
		row.put("online", online.isPresent());

		if (online.isPresent()) {
			Player player = online.get();
			long liveMillis = sessionRegistry.sessionMillis(player.getUniqueId());
			row.put("server", player.getCurrentServer()
				.map(connection -> normalize(connection.getServerInfo().getName()))
				.orElse(""));
			row.put("pingMillis", Math.max(0L, player.getPing()));
			row.put("sessionMillis", liveMillis);
			// The open stint is not yet in total_play_millis; add it so the roster
			// column does not appear frozen while someone plays.
			totalPlayMillis += liveMillis;
		} else {
			row.put("server", "");
			row.put("pingMillis", 0L);
			row.put("sessionMillis", 0L);
		}

		row.put("totalPlayMillis", totalPlayMillis);
		return row;
	}

	private List<Map<String, Object>> moderationEntries(String uuid, int offset, int limit) {
		List<Map<String, Object>> entries = new ArrayList<>();

		for (Map<String, Object> row : recordStore.moderation(uuid, offset, limit)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", longOf(row.get("id")));
			entry.put("action", stringOf(row.get("action")));
			entry.put("actor", stringOf(row.get("actor")));
			entry.put("reason", stringOf(row.get("reason")));
			entry.put("server", stringOf(row.get("server")));
			entry.put("details", stringOf(row.get("details")));
			entry.put("atEpochMillis", longOf(row.get("at")));
			entries.add(entry);
		}

		return entries;
	}

	/** LuckPerms summary for the profile detail: primary group, prefix, suffix. */
	private Map<String, Object> permissionsSummary(String uuidText) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("available", luckPermsService.isAvailable());

		if (!luckPermsService.isAvailable()) {
			return summary;
		}

		try {
			UUID uuid = UUID.fromString(uuidText);
			Optional<LuckPermsUserInfo> info = luckPermsService.getUserInfo(uuid);

			if (info.isPresent()) {
				summary.put("primaryGroup", info.get().groupName());
				summary.put("primaryGroupDisplay", info.get().groupDisplayName());
				summary.put("prefix", info.get().playerPrefix());
				summary.put("suffix", info.get().playerSuffix());
			}
		} catch (IllegalArgumentException badUuid) {
			// A malformed UUID in the directory just means no permissions summary.
		}

		return summary;
	}

	private Optional<Player> resolveOnline(String uuidText) {
		try {
			return proxyServer.getPlayer(UUID.fromString(uuidText));
		} catch (IllegalArgumentException notAUuid) {
			return Optional.empty();
		}
	}

	private String normalizeType(String type) {
		String normalized = normalize(type);
		if ("chat".equals(normalized) || "command".equals(normalized)) {
			return normalized;
		}

		return "";
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private int parseInt(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}

		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private long longOf(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		if (value == null) {
			return 0L;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private String nullableString(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
