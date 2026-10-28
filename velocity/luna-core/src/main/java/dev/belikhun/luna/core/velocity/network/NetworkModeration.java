package dev.belikhun.luna.core.velocity.network;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.players.VelocityPlayerRecordStore;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Network-wide kicks, bans and IP bans, as one service the HTTP API and the
 * proxy commands both go through, so a ban placed from the console, the CLI,
 * an MCP tool or in game behaves the same: stored, enforced at the next login,
 * applied at once to whoever it hits that is online now, and written to the
 * moderation log under the name of whoever placed it.
 */
public final class NetworkModeration {
	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityPlayerRecordStore recordStore;
	private final VelocityNetworkPlayerBanStore playerBans;
	private final VelocityNetworkBanStore ipBans;

	/** What an action did: ok with a payload, or an HTTP-shaped failure. */
	public record Result(boolean ok, int status, String error, Map<String, Object> data) {
		static Result success(Map<String, Object> data) {
			return new Result(true, 200, "", data);
		}

		static Result failure(int status, String error) {
			return new Result(false, status, error, Map.of());
		}
	}

	/** Who a reference names: the identity to record, and where they connect from. */
	private record Target(String uuid, String username, String address, Player online) {}

	public NetworkModeration(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityPlayerRecordStore recordStore,
		VelocityNetworkPlayerBanStore playerBans,
		VelocityNetworkBanStore ipBans
	) {
		this.logger = logger.scope("NetworkModeration");
		this.proxyServer = proxyServer;
		this.recordStore = recordStore;
		this.playerBans = playerBans;
		this.ipBans = ipBans;
	}

	public VelocityNetworkPlayerBanStore playerBans() {
		return playerBans;
	}

	public VelocityNetworkBanStore ipBans() {
		return ipBans;
	}

	/** Disconnect one online player. */
	public Result kick(String reference, String reason, String actor) {
		Optional<Player> online = onlinePlayer(reference);

		if (online.isEmpty()) {
			return Result.failure(404, "player is not online: " + reference);
		}

		Player player = online.get();
		String cleanReason = clean(reason);

		player.disconnect(NetworkBanMessages.kicked(cleanReason));
		logger.audit(actorName(actor) + " kick " + player.getUsername()
			+ (cleanReason.isBlank() ? "" : ": " + cleanReason));
		recordStore.recordModeration(
			player.getUniqueId().toString(),
			player.getUsername(),
			"kick",
			actorName(actor),
			cleanReason,
			currentServer(player),
			""
		);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", player.getUsername());
		payload.put("uuid", player.getUniqueId().toString());
		payload.put("reason", cleanReason);
		return Result.success(payload);
	}

	/**
	 * Ban an account network-wide. The reference is an online player, a known
	 * profile, or a bare username the network has never seen.
	 */
	public Result ban(String reference, String reason, String actor, long expiresAt) {
		if (!playerBans.available()) {
			return Result.failure(503, "network ban database is not available");
		}

		Target target = resolve(reference);

		if (target == null) {
			return Result.failure(400, "not a known player or a valid username: " + reference);
		}

		String cleanReason = clean(reason);
		VelocityNetworkPlayerBanStore.Entry entry = playerBans.add(
			target.username(),
			target.uuid(),
			cleanReason,
			actorName(actor),
			expiresAt
		);

		int kicked = 0;

		if (target.online() != null) {
			target.online().disconnect(NetworkBanMessages.playerBanned(cleanReason, expiresAt));
			kicked = 1;
		}

		recordStore.recordModeration(
			target.uuid(),
			target.username(),
			"network-ban",
			actorName(actor),
			cleanReason,
			"proxy",
			expiresAt > 0 ? "expiresAt=" + expiresAt : ""
		);

		Map<String, Object> payload = playerBanRow(entry);
		payload.put("kicked", kicked);
		return Result.success(payload);
	}

	/** Lift an account ban by name or UUID. */
	public Result unban(String reference, String reason, String actor) {
		if (!playerBans.available()) {
			return Result.failure(503, "network ban database is not available");
		}

		VelocityNetworkPlayerBanStore.Entry removed = playerBans.remove(reference);

		if (removed != null) {
			recordStore.recordModeration(
				removed.uuid,
				removed.username,
				"network-pardon",
				actorName(actor),
				clean(reason),
				"proxy",
				""
			);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", removed == null ? clean(reference) : removed.username);
		payload.put("removed", removed != null);
		return Result.success(payload);
	}

	/**
	 * Ban an address network-wide. The target is a bare IP, or a player whose
	 * current (online) or last known address is banned instead. Everyone online
	 * from that address is disconnected.
	 */
	public Result banIp(String target, String reason, String actor, long expiresAt) {
		if (!ipBans.available()) {
			return Result.failure(503, "network ban database is not available");
		}

		String ip = clean(target);
		String resolvedFrom = "";

		if (!VelocityNetworkBanStore.validIp(ip)) {
			Target player = resolve(ip);

			if (player == null || player.address().isBlank()) {
				return Result.failure(400, "a valid bare IP address, or a player with a known address, is required");
			}

			resolvedFrom = player.username();
			ip = player.address();
		}

		String cleanReason = clean(reason);
		VelocityNetworkBanStore.Entry entry = ipBans.add(ip, cleanReason, actorName(actor), expiresAt);
		List<String> kicked = new ArrayList<>();

		for (Player player : proxyServer.getAllPlayers()) {
			if (!ip.equals(addressOf(player))) {
				continue;
			}

			player.disconnect(NetworkBanMessages.ipBanned(cleanReason, expiresAt));
			kicked.add(player.getUsername());
		}

		recordStore.recordModeration(
			"",
			ip,
			"network-ban-ip",
			actorName(actor),
			cleanReason,
			"proxy",
			resolvedFrom.isBlank() ? "" : "player=" + resolvedFrom
		);

		Map<String, Object> payload = ipBanRow(entry);
		payload.put("resolvedFrom", resolvedFrom);
		payload.put("kicked", kicked);
		return Result.success(payload);
	}

	/** Lift an address ban. */
	public Result unbanIp(String target, String reason, String actor) {
		if (!ipBans.available()) {
			return Result.failure(503, "network ban database is not available");
		}

		String ip = clean(target);

		if (!VelocityNetworkBanStore.validIp(ip)) {
			return Result.failure(400, "a valid bare IP address is required");
		}

		boolean existed = ipBans.remove(ip);

		if (existed) {
			recordStore.recordModeration("", ip, "network-pardon-ip", actorName(actor), clean(reason), "proxy", "");
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ip", ip);
		payload.put("removed", existed);
		return Result.success(payload);
	}

	/** One account ban as a plain row for JSON. */
	public static Map<String, Object> playerBanRow(VelocityNetworkPlayerBanStore.Entry entry) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("username", entry.username);
		row.put("uuid", entry.uuid);
		row.put("reason", entry.reason);
		row.put("actor", entry.actor);
		row.put("createdAtEpochMillis", entry.createdAt);
		row.put("expiresAtEpochMillis", entry.expiresAt);
		row.put("hits", entry.hits);
		row.put("lastHitAtEpochMillis", entry.lastHitAt);
		return row;
	}

	/** One address ban as a plain row for JSON. */
	public static Map<String, Object> ipBanRow(VelocityNetworkBanStore.Entry entry) {
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

	/** An online player by exact name (any case) or UUID. */
	public Optional<Player> onlinePlayer(String reference) {
		String trimmed = clean(reference);

		if (trimmed.isBlank()) {
			return Optional.empty();
		}

		UUID uuid = parseUuid(trimmed);

		if (uuid != null) {
			return proxyServer.getPlayer(uuid);
		}

		return proxyServer.getPlayer(trimmed);
	}

	/** Online first, then the player directory, then a bare valid username. */
	private Target resolve(String reference) {
		String trimmed = clean(reference);

		if (trimmed.isBlank()) {
			return null;
		}

		Optional<Player> online = onlinePlayer(trimmed);

		if (online.isPresent()) {
			Player player = online.get();
			return new Target(player.getUniqueId().toString(), player.getUsername(), addressOf(player), player);
		}

		if (recordStore.available()) {
			try {
				Optional<Map<String, Object>> profile = recordStore.findProfile(trimmed);

				if (profile.isPresent()) {
					Map<String, Object> row = profile.get();
					return new Target(
						stringOf(row.get("uuid")),
						stringOf(row.get("username")),
						stringOf(row.get("last_address")),
						null
					);
				}
			} catch (RuntimeException exception) {
				logger.warn("Không tra được hồ sơ người chơi " + trimmed + ": " + exception.getMessage());
			}
		}

		UUID uuid = parseUuid(trimmed);

		if (uuid != null || !VelocityNetworkPlayerBanStore.validUsername(trimmed)) {
			return null;
		}

		return new Target("", trimmed, "", null);
	}

	private static String addressOf(Player player) {
		InetSocketAddress remote = player.getRemoteAddress();

		if (remote == null || remote.getAddress() == null) {
			return "";
		}

		return remote.getAddress().getHostAddress().toLowerCase(Locale.ROOT);
	}

	private static String currentServer(Player player) {
		return player.getCurrentServer()
			.map(connection -> connection.getServerInfo().getName())
			.orElse("");
	}

	private static UUID parseUuid(String text) {
		if (text.length() != 36) {
			return null;
		}

		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String actorName(String actor) {
		String trimmed = clean(actor);
		return trimmed.isBlank() ? "console" : trimmed;
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}

	private static String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
