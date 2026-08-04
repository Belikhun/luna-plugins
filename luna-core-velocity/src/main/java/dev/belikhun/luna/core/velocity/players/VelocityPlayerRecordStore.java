package dev.belikhun.luna.core.velocity.players;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persists the player directory: who has ever joined, when, where, for how long,
 * what they said and which moderation actions hit them.
 *
 * The in-memory {@link VelocityPlayerSessionRegistry} answers "who is on right
 * now"; this store is its durable counterpart, writing to the shared MariaDB
 * database so the console can show players that are offline and history older
 * than the current proxy uptime.
 *
 * The store is created once for the plugin's lifetime and survives config
 * reloads; only the {@link Database} handle is swapped via {@link #attach}.
 * All writes run on a single background thread — event handlers must never
 * block a netty thread on JDBC.
 */
public final class VelocityPlayerRecordStore {
	/** Commands whose arguments must never reach the log (they carry passwords). */
	private static final Set<String> REDACTED_COMMANDS = Set.of(
		"login", "l", "register", "reg", "changepassword", "changepw", "unregister", "premium", "2fa"
	);

	/** Game-profile property that carries the skin texture payload. */
	private static final String TEXTURES_PROPERTY = "textures";

	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final ExecutorService writer;
	private volatile Database database;

	public VelocityPlayerRecordStore(ProxyServer proxyServer, LunaLogger logger) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("PlayerRecords");
		this.database = new NoopDatabase();
		this.writer = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-player-records");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Swap the database handle on reload and heal stints orphaned by a crash. */
	public void attach(Database nextDatabase) {
		this.database = nextDatabase == null ? new NoopDatabase() : nextDatabase;

		if (!available()) {
			logger.warn("Database đang tắt — hồ sơ người chơi sẽ không được ghi lại.");
			return;
		}

		submit("heal-open-sessions", () -> healOpenSessions());
	}

	/** Whether a usable database is attached. */
	public boolean available() {
		return !(database instanceof NoopDatabase);
	}

	/** Flush pending writes and stop the writer thread; called on proxy shutdown. */
	public void shutdown() {
		for (Player player : proxyServer.getAllPlayers()) {
			recordDisconnect(player.getUniqueId(), player.getUsername());
		}

		writer.shutdown();

		try {
			if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
				writer.shutdownNow();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			writer.shutdownNow();
		}
	}

	// ------------------------------------------------------------------ events

	@Subscribe(order = PostOrder.LAST)
	public void onPostLogin(PostLoginEvent event) {
		Player player = event.getPlayer();
		long now = System.currentTimeMillis();

		String address = remoteAddress(player);
		String clientVersion = player.getProtocolVersion().getVersionIntroducedIn();
		boolean onlineMode = player.isOnlineMode();
		Optional<GameProfile.Property> textures = texturesProperty(player);
		String skinTexture = textures.map(GameProfile.Property::getValue).orElse(null);
		String skinSignature = textures.map(GameProfile.Property::getSignature).orElse(null);

		submit("record-login", () -> upsertProfile(
			player.getUniqueId(),
			player.getUsername(),
			now,
			"",
			address,
			clientVersion,
			onlineMode,
			skinTexture,
			skinSignature
		));
	}

	@Subscribe(order = PostOrder.LAST)
	public void onServerConnected(ServerConnectedEvent event) {
		Player player = event.getPlayer();
		String server = normalize(event.getServer().getServerInfo().getName());
		long now = System.currentTimeMillis();

		// SkinsRestorer applies skins during login, before the first backend connect,
		// so the profile's textures are re-checked here in case login saw none.
		Optional<GameProfile.Property> textures = texturesProperty(player);
		String skinTexture = textures.map(GameProfile.Property::getValue).orElse(null);
		String skinSignature = textures.map(GameProfile.Property::getSignature).orElse(null);

		submit("record-switch", () -> {
			closeOpenSessions(player.getUniqueId(), now);
			openSession(player.getUniqueId(), player.getUsername(), server, now);
			touchProfile(player.getUniqueId(), now, server, skinTexture, skinSignature);
		});
	}

	@Subscribe(order = PostOrder.LAST)
	public void onDisconnect(DisconnectEvent event) {
		recordDisconnect(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
	}

	@Subscribe(order = PostOrder.LAST)
	public void onPlayerChat(PlayerChatEvent event) {
		if (!event.getResult().isAllowed()) {
			return;
		}

		Player player = event.getPlayer();
		String server = currentServer(player);
		String message = event.getMessage();
		long now = System.currentTimeMillis();

		submit("record-chat", () -> insertChat(player.getUniqueId(), player.getUsername(), server, "chat", message, now));
	}

	@Subscribe(order = PostOrder.LAST)
	public void onCommandExecute(CommandExecuteEvent event) {
		if (!event.getResult().isAllowed()) {
			return;
		}

		if (!(event.getCommandSource() instanceof Player player)) {
			return;
		}

		String server = currentServer(player);
		String command = redactSensitive(event.getCommand());
		long now = System.currentTimeMillis();

		submit("record-command", () -> insertChat(player.getUniqueId(), player.getUsername(), server, "command", command, now));
	}

	// ------------------------------------------------------------------ writes

	/** Append a moderation-log entry; used by the HTTP API and the admin endpoints. */
	public void recordModeration(
		String targetUuid,
		String targetName,
		String action,
		String actor,
		String reason,
		String server,
		String details
	) {
		long now = System.currentTimeMillis();

		submit("record-moderation", () -> database.update(
			"INSERT INTO luna_player_moderation (target_uuid, target_name, action, actor, reason, server, details, at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
			List.of(
				blankIfNull(targetUuid),
				blankIfNull(targetName),
				blankIfNull(action),
				blankIfNull(actor),
				blankIfNull(reason),
				normalize(server),
				blankIfNull(details),
				now
			)
		));
	}

	/** Overwrite a profile's recorded skin — used when an admin changes it. */
	public void updateSkin(UUID uuid, String value, String signature) {
		submit("update-skin", () -> database.update(
			"UPDATE luna_player_profiles SET skin_texture = ?, skin_signature = ? WHERE uuid = ?",
			listOfNullable(value, signature, uuid.toString())
		));
	}

	private void recordDisconnect(UUID uuid, String username) {
		long now = System.currentTimeMillis();

		submit("record-leave", () -> {
			closeOpenSessions(uuid, now);
			database.update(
				"UPDATE luna_player_profiles SET last_seen_at = ?, username = ?, username_lower = ? WHERE uuid = ?",
				List.of(now, username, username.toLowerCase(Locale.ROOT), uuid.toString())
			);
		});
	}

	private void upsertProfile(
		UUID uuid,
		String username,
		long now,
		String server,
		String address,
		String clientVersion,
		boolean onlineMode,
		String skinTexture,
		String skinSignature
	) {
		database.update(
			"INSERT INTO luna_player_profiles"
				+ " (uuid, username, username_lower, first_seen_at, last_seen_at, last_server, last_address,"
				+ " last_client_version, online_mode, session_count, total_play_millis, skin_texture, skin_signature)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?)"
				+ " ON DUPLICATE KEY UPDATE"
				+ " username = VALUES(username), username_lower = VALUES(username_lower),"
				+ " last_seen_at = VALUES(last_seen_at), last_address = VALUES(last_address),"
				+ " last_client_version = VALUES(last_client_version), online_mode = VALUES(online_mode),"
				+ " session_count = session_count + 1,"
				+ " skin_texture = COALESCE(VALUES(skin_texture), skin_texture),"
				+ " skin_signature = COALESCE(VALUES(skin_signature), skin_signature)",
			listOfNullable(
				uuid.toString(),
				username,
				username.toLowerCase(Locale.ROOT),
				now,
				now,
				normalize(server),
				blankIfNull(address),
				blankIfNull(clientVersion),
				onlineMode ? 1 : 0,
				skinTexture,
				skinSignature
			)
		);
	}

	private void touchProfile(UUID uuid, long now, String server, String skinTexture, String skinSignature) {
		database.update(
			"UPDATE luna_player_profiles SET last_seen_at = ?, last_server = ?,"
				+ " skin_texture = COALESCE(?, skin_texture), skin_signature = COALESCE(?, skin_signature)"
				+ " WHERE uuid = ?",
			listOfNullable(now, normalize(server), skinTexture, skinSignature, uuid.toString())
		);
	}

	private void openSession(UUID uuid, String username, String server, long now) {
		database.update(
			"INSERT INTO luna_player_sessions (uuid, username, server, connected_at) VALUES (?, ?, ?, ?)",
			List.of(uuid.toString(), username, normalize(server), now)
		);
	}

	/** Close every open stint for a player and add the played time to their total. */
	private void closeOpenSessions(UUID uuid, long now) {
		List<Map<String, Object>> open = database.query(
			"SELECT id, connected_at FROM luna_player_sessions WHERE uuid = ? AND disconnected_at IS NULL",
			List.of(uuid.toString())
		);

		long playedMillis = 0L;

		for (Map<String, Object> row : open) {
			long id = longOf(row.get("id"));
			long connectedAt = longOf(row.get("connected_at"));
			long duration = Math.max(0L, now - connectedAt);
			playedMillis += duration;

			database.update(
				"UPDATE luna_player_sessions SET disconnected_at = ?, duration_millis = ? WHERE id = ?",
				List.of(now, duration, id)
			);
		}

		if (playedMillis > 0L) {
			database.update(
				"UPDATE luna_player_profiles SET total_play_millis = total_play_millis + ? WHERE uuid = ?",
				List.of(playedMillis, uuid.toString())
			);
		}
	}

	private void insertChat(UUID uuid, String username, String server, String type, String content, long at) {
		database.update(
			"INSERT INTO luna_player_chat (uuid, username, server, type, content, at) VALUES (?, ?, ?, ?, ?, ?)",
			List.of(uuid.toString(), username, normalize(server), type, content, at)
		);
	}

	/**
	 * Close stints left open by a proxy crash. Players still connected keep their
	 * open stint; anyone else's open row gets closed with zero duration, because
	 * the moment the proxy died is unknown and playtime must never be invented.
	 */
	private void healOpenSessions() {
		List<Map<String, Object>> open = database.query(
			"SELECT id, uuid FROM luna_player_sessions WHERE disconnected_at IS NULL",
			List.of()
		);

		if (open.isEmpty()) {
			return;
		}

		Set<String> online = new java.util.HashSet<>();
		for (Player player : proxyServer.getAllPlayers()) {
			online.add(player.getUniqueId().toString());
		}

		int healed = 0;

		for (Map<String, Object> row : open) {
			String uuid = String.valueOf(row.get("uuid"));
			if (online.contains(uuid)) {
				continue;
			}

			database.update(
				"UPDATE luna_player_sessions SET disconnected_at = connected_at, duration_millis = 0 WHERE id = ?",
				List.of(longOf(row.get("id")))
			);
			healed++;
		}

		if (healed > 0) {
			logger.audit("Đã đóng " + healed + " phiên chơi bị bỏ ngỏ sau khi proxy khởi động lại.");
		}
	}

	// ------------------------------------------------------------------ reads

	/** Page through profiles, optionally filtered by a username/uuid search term. */
	public List<Map<String, Object>> listProfiles(String search, String sort, boolean ascending, int offset, int limit) {
		String orderColumn = switch (blankIfNull(sort)) {
			case "username" -> "username_lower";
			case "firstSeen" -> "first_seen_at";
			case "playtime" -> "total_play_millis";
			case "sessions" -> "session_count";
			default -> "last_seen_at";
		};
		String direction = ascending ? "ASC" : "DESC";

		if (blankIfNull(search).isBlank()) {
			return database.query(
				"SELECT * FROM luna_player_profiles ORDER BY " + orderColumn + " " + direction + " LIMIT ? OFFSET ?",
				List.of(limit, offset)
			);
		}

		String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		return database.query(
			"SELECT * FROM luna_player_profiles WHERE username_lower LIKE ? OR uuid LIKE ?"
				+ " ORDER BY " + orderColumn + " " + direction + " LIMIT ? OFFSET ?",
			List.of(term, term, limit, offset)
		);
	}

	/** How many profiles match a search term (empty term counts everyone). */
	public long countProfiles(String search) {
		if (blankIfNull(search).isBlank()) {
			return scalar("SELECT COUNT(*) AS n FROM luna_player_profiles", List.of());
		}

		String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		return scalar(
			"SELECT COUNT(*) AS n FROM luna_player_profiles WHERE username_lower LIKE ? OR uuid LIKE ?",
			List.of(term, term)
		);
	}

	/** One profile by UUID or (case-insensitive) username. */
	public Optional<Map<String, Object>> findProfile(String reference) {
		String trimmed = blankIfNull(reference).trim();
		if (trimmed.isBlank()) {
			return Optional.empty();
		}

		Optional<Map<String, Object>> byUuid = database.first(
			"SELECT * FROM luna_player_profiles WHERE uuid = ?",
			List.of(trimmed)
		);

		if (byUuid.isPresent()) {
			return byUuid;
		}

		return database.first(
			"SELECT * FROM luna_player_profiles WHERE username_lower = ? ORDER BY last_seen_at DESC",
			List.of(trimmed.toLowerCase(Locale.ROOT))
		);
	}

	/** Closed-stint playtime aggregated per backend. */
	public List<Map<String, Object>> playtimeByServer(String uuid) {
		return database.query(
			"SELECT server, SUM(duration_millis) AS play_millis, COUNT(*) AS stints"
				+ " FROM luna_player_sessions WHERE uuid = ? AND disconnected_at IS NOT NULL"
				+ " GROUP BY server ORDER BY play_millis DESC",
			List.of(uuid)
		);
	}

	/** Page of play sessions, newest first. */
	public List<Map<String, Object>> sessions(String uuid, int offset, int limit) {
		return database.query(
			"SELECT * FROM luna_player_sessions WHERE uuid = ? ORDER BY connected_at DESC LIMIT ? OFFSET ?",
			List.of(uuid, limit, offset)
		);
	}

	/** Total number of recorded sessions for a player. */
	public long sessionCount(String uuid) {
		return scalar("SELECT COUNT(*) AS n FROM luna_player_sessions WHERE uuid = ?", List.of(uuid));
	}

	/** Page of chat/command entries, newest first; {@code type} filters when non-blank. */
	public List<Map<String, Object>> chat(String uuid, String type, int offset, int limit) {
		if (blankIfNull(type).isBlank()) {
			return database.query(
				"SELECT * FROM luna_player_chat WHERE uuid = ? ORDER BY at DESC LIMIT ? OFFSET ?",
				List.of(uuid, limit, offset)
			);
		}

		return database.query(
			"SELECT * FROM luna_player_chat WHERE uuid = ? AND type = ? ORDER BY at DESC LIMIT ? OFFSET ?",
			List.of(uuid, type, limit, offset)
		);
	}

	/** Total chat/command entries for a player, optionally one type only. */
	public long chatCount(String uuid, String type) {
		if (blankIfNull(type).isBlank()) {
			return scalar("SELECT COUNT(*) AS n FROM luna_player_chat WHERE uuid = ?", List.of(uuid));
		}

		return scalar("SELECT COUNT(*) AS n FROM luna_player_chat WHERE uuid = ? AND type = ?", List.of(uuid, type));
	}

	/** Page of moderation entries for a target, newest first. */
	public List<Map<String, Object>> moderation(String uuid, int offset, int limit) {
		return database.query(
			"SELECT * FROM luna_player_moderation WHERE target_uuid = ? ORDER BY at DESC LIMIT ? OFFSET ?",
			List.of(uuid, limit, offset)
		);
	}

	/** Total moderation entries for a target. */
	public long moderationCount(String uuid) {
		return scalar("SELECT COUNT(*) AS n FROM luna_player_moderation WHERE target_uuid = ?", List.of(uuid));
	}

	/** Known usernames for a set of UUIDs, from the profile table. */
	public Map<String, String> usernames(java.util.Collection<String> uuids) {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		if (uuids == null || uuids.isEmpty()) {
			return out;
		}

		StringBuilder placeholders = new StringBuilder();
		List<Object> bindings = new ArrayList<>(uuids.size());

		for (String uuid : uuids) {
			if (!placeholders.isEmpty()) {
				placeholders.append(", ");
			}
			placeholders.append('?');
			bindings.add(uuid);
		}

		List<Map<String, Object>> rows = database.query(
			"SELECT uuid, username FROM luna_player_profiles WHERE uuid IN (" + placeholders + ")",
			bindings
		);

		for (Map<String, Object> row : rows) {
			out.put(String.valueOf(row.get("uuid")), String.valueOf(row.get("username")));
		}

		return out;
	}

	// ------------------------------------------------------------------ helpers

	private void submit(String what, Runnable work) {
		if (!available()) {
			return;
		}

		try {
			writer.execute(() -> {
				try {
					work.run();
				} catch (RuntimeException exception) {
					logger.error("Ghi hồ sơ người chơi thất bại (" + what + "): " + exception.getMessage(), exception);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
			// Writes after shutdown are dropped on purpose; the proxy is going away.
		}
	}

	private Optional<GameProfile.Property> texturesProperty(Player player) {
		for (GameProfile.Property property : player.getGameProfile().getProperties()) {
			if (TEXTURES_PROPERTY.equals(property.getName()) && !property.getValue().isBlank()) {
				return Optional.of(property);
			}
		}

		return Optional.empty();
	}

	/** Strip the arguments off commands that carry credentials. */
	private String redactSensitive(String command) {
		String trimmed = blankIfNull(command).trim();
		int space = trimmed.indexOf(' ');
		if (space < 0) {
			return trimmed;
		}

		String word = trimmed.substring(0, space).toLowerCase(Locale.ROOT);
		if (REDACTED_COMMANDS.contains(word)) {
			return word + " <đã ẩn>";
		}

		return trimmed;
	}

	private String currentServer(Player player) {
		return player.getCurrentServer()
			.map(connection -> normalize(connection.getServerInfo().getName()))
			.orElse("");
	}

	private String remoteAddress(Player player) {
		InetSocketAddress address = player.getRemoteAddress();
		if (address == null) {
			return "";
		}

		return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
	}

	private long scalar(String sql, List<Object> bindings) {
		return database.first(sql, bindings)
			.map(row -> longOf(row.get("n")))
			.orElse(0L);
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

	private String blankIfNull(String value) {
		return value == null ? "" : value;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	/** Like {@link List#of} but tolerating nulls, which JDBC binds as SQL NULL. */
	private List<Object> listOfNullable(Object... values) {
		List<Object> out = new ArrayList<>(values.length);
		for (Object value : values) {
			out.add(value);
		}
		return out;
	}
}
