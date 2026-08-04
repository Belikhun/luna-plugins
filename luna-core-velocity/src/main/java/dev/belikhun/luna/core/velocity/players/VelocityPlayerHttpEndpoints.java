package dev.belikhun.luna.core.velocity.players;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.http.SseBroadcaster;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is on the network, where, and for how long.
 *
 * The proxy is the only place with a network-wide view of players, so the console
 * reads this instead of asking each backend. Payloads include the connecting IP,
 * which is why — like every dashboard route — these are token-gated.
 */
public final class VelocityPlayerHttpEndpoints {
	/** Default page size for the activity log. */
	private static final int DEFAULT_HISTORY_LIMIT = 50;

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityPlayerSessionRegistry sessionRegistry;
	private final RequestAuthorizer authorizer;
	private final SseBroadcaster broadcaster;

	public VelocityPlayerHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityPlayerSessionRegistry sessionRegistry,
		RequestAuthorizer authorizer,
		SseBroadcaster broadcaster
	) {
		this.logger = logger.scope("PlayerHttp");
		this.proxyServer = proxyServer;
		this.sessionRegistry = sessionRegistry;
		this.authorizer = authorizer;
		this.broadcaster = broadcaster;
	}

	public void register(Router router) {
		router.get("/players", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /players do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String serverFilter = normalize(request.queryParam("server", ""));

			List<Map<String, Object>> players = new ArrayList<>();
			for (Player player : proxyServer.getAllPlayers()) {
				Map<String, Object> row = buildPlayer(player);
				if (!serverFilter.isBlank() && !serverFilter.equals(row.get("server"))) {
					continue;
				}
				players.add(row);
			}

			players.sort((left, right) -> String.valueOf(left.get("username"))
				.compareToIgnoreCase(String.valueOf(right.get("username"))));

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("onlineCount", sessionRegistry.onlineCount());
			payload.put("byServer", countByServer());
			payload.put("players", players);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.get("/players/history", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /players/history do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			int limit = parseInt(request.queryParam("limit", ""), DEFAULT_HISTORY_LIMIT);

			List<Map<String, Object>> entries = new ArrayList<>();
			for (VelocityPlayerSessionRegistry.Activity activity : sessionRegistry.recentActivity(limit)) {
				entries.add(buildActivity(activity));
			}

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("activity", entries);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.get("/players/stream", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /players/stream do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			logger.debug("Console mở stream người chơi.");

			return broadcaster.subscribe(stream -> {
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("generatedAtEpochMillis", System.currentTimeMillis());
				payload.put("onlineCount", sessionRegistry.onlineCount());
				payload.put("byServer", countByServer());
				payload.put("players", currentPlayers());
				stream.event("snapshot", LunaJson.write(payload));
			});
		});

		// {player} is a username or a UUID — the console has whichever the row carried.
		router.get("/players/{player}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /players/{player} do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "").trim();
			Optional<Player> found = resolvePlayer(reference);

			if (found.isEmpty()) {
				return LunaJson.error(404, "player not connected: " + reference);
			}

			return LunaJson.envelope(200, buildPlayer(found.get()), startedAt);
		});
	}

	/** Push one activity entry to every subscribed console. */
	public void onActivity(VelocityPlayerSessionRegistry.Activity activity) {
		if (activity == null || broadcaster.size() == 0) {
			return;
		}

		Map<String, Object> payload = new LinkedHashMap<>(buildActivity(activity));
		payload.put("onlineCount", sessionRegistry.onlineCount());
		payload.put("byServer", countByServer());

		broadcaster.broadcast(activity.type(), LunaJson.write(payload));
	}

	/** Resolve a username (case-insensitive) or UUID to a connected player. */
	public Optional<Player> resolvePlayer(String reference) {
		if (reference == null || reference.isBlank()) {
			return Optional.empty();
		}

		String trimmed = reference.trim();

		try {
			return proxyServer.getPlayer(UUID.fromString(trimmed));
		} catch (IllegalArgumentException notAUuid) {
			return proxyServer.getPlayer(trimmed);
		}
	}

	private List<Map<String, Object>> currentPlayers() {
		List<Map<String, Object>> players = new ArrayList<>();
		for (Player player : proxyServer.getAllPlayers()) {
			players.add(buildPlayer(player));
		}
		return players;
	}

	private Map<String, Object> buildPlayer(Player player) {
		String server = player.getCurrentServer()
			.map(connection -> normalize(connection.getServerInfo().getName()))
			.orElse("");

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("uuid", player.getUniqueId().toString());
		row.put("username", player.getUsername());
		row.put("server", server);
		row.put("pingMillis", Math.max(0L, player.getPing()));
		row.put("sessionMillis", sessionRegistry.sessionMillis(player.getUniqueId()));
		row.put("connectedAtEpochMillis", sessionRegistry.session(player.getUniqueId())
			.map(VelocityPlayerSessionRegistry.Session::connectedAtEpochMillis)
			.orElse(0L));
		row.put("remoteAddress", remoteAddress(player));
		row.put("virtualHost", player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(""));
		row.put("protocolVersion", player.getProtocolVersion().getProtocol());
		row.put("clientVersion", player.getProtocolVersion().getVersionIntroducedIn());
		row.put("onlineMode", player.isOnlineMode());
		return row;
	}

	private Map<String, Object> buildActivity(VelocityPlayerSessionRegistry.Activity activity) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("type", activity.type());
		entry.put("uuid", activity.uuid().toString());
		entry.put("username", activity.username());
		entry.put("server", activity.server());
		entry.put("previousServer", activity.previousServer());
		entry.put("atEpochMillis", activity.atEpochMillis());
		entry.put("sessionMillis", activity.sessionMillis());
		return entry;
	}

	private Map<String, Object> countByServer() {
		Map<String, Object> counts = new LinkedHashMap<>();

		for (Player player : proxyServer.getAllPlayers()) {
			String server = player.getCurrentServer()
				.map(connection -> normalize(connection.getServerInfo().getName()))
				.orElse("");

			if (server.isBlank()) {
				continue;
			}

			counts.merge(server, 1, (left, right) -> ((Integer) left) + ((Integer) right));
		}

		return counts;
	}

	private String remoteAddress(Player player) {
		InetSocketAddress address = player.getRemoteAddress();
		if (address == null) {
			return "";
		}

		return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
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

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
