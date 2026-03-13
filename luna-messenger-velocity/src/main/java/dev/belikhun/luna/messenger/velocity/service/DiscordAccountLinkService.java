package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DiscordAccountLinkService {
	private static final String LINKS_TABLE = "messenger_discord_links";
	private static final String USER_PROFILES_TABLE = "user_profiles";

	private final LunaLogger logger;
	private final Database database;
	private final boolean databaseEnabled;
	private final long codeTtlMs;
	private final ConcurrentMap<String, PendingLinkCode> pendingByCode;
	private final ConcurrentMap<UUID, PendingLinkCode> pendingByPlayer;

	private DiscordAccountLinkService(LunaLogger logger, Database database, boolean databaseEnabled, long codeTtlMs) {
		this.logger = logger.scope("DiscordLinkService");
		this.database = database;
		this.databaseEnabled = databaseEnabled;
		this.codeTtlMs = Math.max(30_000L, codeTtlMs);
		this.pendingByCode = new ConcurrentHashMap<>();
		this.pendingByPlayer = new ConcurrentHashMap<>();
	}

	public static DiscordAccountLinkService create(Database sharedDatabase, java.nio.file.Path configPath, LunaLogger logger) {
		Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
		long codeTtlMs = ConfigValues.intValue(ConfigValues.map(root, "discord-link"), "code-expiration-seconds", 300) * 1000L;

		if (sharedDatabase == null || sharedDatabase instanceof NoopDatabase) {
			logger.warn("LunaCore database đang tắt hoặc chưa sẵn sàng. Link Discord sẽ không hoạt động.");
			return new DiscordAccountLinkService(logger, new NoopDatabase(), false, codeTtlMs);
		}

		try {
			DatabaseMigrator migrator = new DatabaseMigrator(sharedDatabase, logger.scope("DiscordLinkMigration"));
			DiscordLinkDatabaseMigrations.register(migrator);
			migrator.migrateNamespace("lunamessenger");
			logger.success("Đã gắn Discord link vào database instance của LunaCore.");
			return new DiscordAccountLinkService(logger, sharedDatabase, true, codeTtlMs);
		} catch (Exception exception) {
			logger.error("Không thể khởi tạo bảng/migration cho Discord link trên LunaCore database.", exception);
			return new DiscordAccountLinkService(logger, new NoopDatabase(), false, codeTtlMs);
		}
	}

	public BeginLinkResult beginLink(UUID playerId, String playerName) {
		if (!databaseEnabled) {
			return BeginLinkResult.ofDatabaseDisabled();
		}

		pruneExpiredCodes();
		String code = generateCode();
		long expiresAt = System.currentTimeMillis() + codeTtlMs;
		PendingLinkCode pending = new PendingLinkCode(code, playerId, playerName, expiresAt);
		PendingLinkCode previous = pendingByPlayer.put(playerId, pending);
		if (previous != null) {
			pendingByCode.remove(previous.code(), previous);
		}
		pendingByCode.put(code, pending);
		return BeginLinkResult.success(code, expiresAt);
	}

	public LinkByCodeResult linkByCode(String discordUserId, String discordUsername, String rawCode) {
		if (!databaseEnabled) {
			return LinkByCodeResult.ofDatabaseDisabled();
		}

		String code = normalizeCode(rawCode);
		if (code.isEmpty()) {
			return LinkByCodeResult.ofInvalidCode();
		}

		pruneExpiredCodes();
		PendingLinkCode pending = pendingByCode.get(code);
		if (pending == null) {
			return LinkByCodeResult.ofInvalidCode();
		}
		if (pending.expiresAtEpochMs() <= System.currentTimeMillis()) {
			pendingByCode.remove(code, pending);
			pendingByPlayer.remove(pending.playerId(), pending);
			return LinkByCodeResult.ofExpiredCode();
		}

		Optional<LinkedAccount> linkedDiscord = findByDiscordId(discordUserId);
		if (linkedDiscord.isPresent() && !linkedDiscord.get().playerId().equals(pending.playerId())) {
			return LinkByCodeResult.discordAlreadyLinked(linkedDiscord.get());
		}

		long now = Instant.now().toEpochMilli();
		database.update(
			"DELETE FROM " + LINKS_TABLE + " WHERE discord_user_id = ? AND minecraft_uuid <> ?",
			List.of(discordUserId, pending.playerId().toString())
		);
		int updated = database.update(
			"UPDATE " + LINKS_TABLE + " SET minecraft_name = ?, discord_user_id = ?, discord_username = ?, updated_at = ? WHERE minecraft_uuid = ?",
			List.of(pending.playerName(), discordUserId, normalizeDiscordUsername(discordUsername), now, pending.playerId().toString())
		);
		if (updated == 0) {
			database.update(
				"INSERT INTO " + LINKS_TABLE + " (minecraft_uuid, minecraft_name, discord_user_id, discord_username, linked_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
				List.of(
					pending.playerId().toString(),
					pending.playerName(),
					discordUserId,
					normalizeDiscordUsername(discordUsername),
					now,
					now
				)
			);
		}

		pendingByCode.remove(code, pending);
		pendingByPlayer.remove(pending.playerId(), pending);

		LinkedAccount account = new LinkedAccount(
			pending.playerId(),
			pending.playerName(),
			discordUserId,
			normalizeDiscordUsername(discordUsername),
			now,
			now
		);
		return LinkByCodeResult.success(account);
	}

	public UnlinkResult unlinkByPlayer(UUID playerId) {
		if (!databaseEnabled) {
			return UnlinkResult.ofDatabaseDisabled();
		}

		Optional<LinkedAccount> existing = findByPlayerId(playerId);
		if (existing.isEmpty()) {
			clearPendingByPlayer(playerId);
			return UnlinkResult.ofNotLinked();
		}

		database.update("DELETE FROM " + LINKS_TABLE + " WHERE minecraft_uuid = ?", List.of(playerId.toString()));
		clearPendingByPlayer(playerId);
		return UnlinkResult.success(existing.get());
	}

	public UnlinkResult unlinkByDiscordId(String discordUserId) {
		if (!databaseEnabled) {
			return UnlinkResult.ofDatabaseDisabled();
		}

		Optional<LinkedAccount> existing = findByDiscordId(discordUserId);
		if (existing.isEmpty()) {
			return UnlinkResult.ofNotLinked();
		}

		database.update("DELETE FROM " + LINKS_TABLE + " WHERE discord_user_id = ?", List.of(discordUserId));
		clearPendingByPlayer(existing.get().playerId());
		return UnlinkResult.success(existing.get());
	}

	public Optional<LinkedAccount> findByPlayerId(UUID playerId) {
		if (!databaseEnabled) {
			return Optional.empty();
		}

		return database.first(
			"SELECT minecraft_uuid, minecraft_name, discord_user_id, discord_username, linked_at, updated_at FROM " + LINKS_TABLE + " WHERE minecraft_uuid = ?",
			List.of(playerId.toString())
		).flatMap(this::mapLinkedAccount);
	}

	public Optional<LinkedAccount> findByDiscordId(String discordUserId) {
		if (!databaseEnabled || discordUserId == null || discordUserId.isBlank()) {
			return Optional.empty();
		}

		return database.first(
			"SELECT minecraft_uuid, minecraft_name, discord_user_id, discord_username, linked_at, updated_at FROM " + LINKS_TABLE + " WHERE discord_user_id = ?",
			List.of(discordUserId)
		).flatMap(this::mapLinkedAccount);
	}

	public Optional<LinkedAccount> findByMinecraftName(String minecraftName) {
		if (!databaseEnabled || minecraftName == null || minecraftName.isBlank()) {
			return Optional.empty();
		}

		Optional<String> profileUuid = database.first(
			"SELECT uuid FROM " + USER_PROFILES_TABLE + " WHERE LOWER(name) = LOWER(?) LIMIT 1",
			List.of(minecraftName.trim())
		).map(row -> String.valueOf(row.get("uuid")));
		if (profileUuid.isPresent()) {
			try {
				return findByPlayerId(UUID.fromString(profileUuid.get()));
			} catch (IllegalArgumentException ignored) {
			}
		}

		return database.first(
			"SELECT minecraft_uuid, minecraft_name, discord_user_id, discord_username, linked_at, updated_at FROM " + LINKS_TABLE + " WHERE LOWER(minecraft_name) = LOWER(?) LIMIT 1",
			List.of(minecraftName.trim())
		).flatMap(this::mapLinkedAccount);
	}

	public boolean isDatabaseEnabled() {
		return databaseEnabled;
	}

	public void close() {
		pendingByCode.clear();
		pendingByPlayer.clear();
	}

	private Optional<LinkedAccount> mapLinkedAccount(Map<String, Object> row) {
		if (row == null || row.isEmpty()) {
			return Optional.empty();
		}

		String rawUuid = String.valueOf(row.get("minecraft_uuid"));
		if (rawUuid == null || rawUuid.isBlank()) {
			return Optional.empty();
		}

		try {
			UUID uuid = UUID.fromString(rawUuid);
			String playerName = normalizePlayerName(row.get("minecraft_name"));
			String discordUserId = normalizeDiscordId(row.get("discord_user_id"));
			String discordUsername = normalizeDiscordUsername(row.get("discord_username"));
			long linkedAt = longValue(row.get("linked_at"));
			long updatedAt = longValue(row.get("updated_at"));
			if (discordUserId.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(new LinkedAccount(uuid, playerName, discordUserId, discordUsername, linkedAt, updatedAt));
		} catch (Exception exception) {
			logger.debug("Bỏ qua bản ghi Discord link lỗi dữ liệu: " + exception.getMessage());
			return Optional.empty();
		}
	}

	private void pruneExpiredCodes() {
		long now = System.currentTimeMillis();
		pendingByCode.entrySet().removeIf(entry -> {
			PendingLinkCode pending = entry.getValue();
			if (pending == null || pending.expiresAtEpochMs() > now) {
				return false;
			}
			pendingByPlayer.remove(pending.playerId(), pending);
			return true;
		});
	}

	private void clearPendingByPlayer(UUID playerId) {
		PendingLinkCode pending = pendingByPlayer.remove(playerId);
		if (pending == null) {
			return;
		}
		pendingByCode.remove(pending.code(), pending);
	}

	private String generateCode() {
		for (int attempt = 0; attempt < 1000; attempt++) {
			String code = String.format("%04d", ThreadLocalRandom.current().nextInt(0, 10_000));
			if (!pendingByCode.containsKey(code)) {
				return code;
			}
		}
		return String.format("%04d", ThreadLocalRandom.current().nextInt(0, 10_000));
	}

	private String normalizeCode(String rawCode) {
		if (rawCode == null) {
			return "";
		}
		String code = rawCode.trim();
		if (code.length() != 4) {
			return "";
		}
		for (int index = 0; index < code.length(); index++) {
			if (!Character.isDigit(code.charAt(index))) {
				return "";
			}
		}
		return code;
	}

	private String normalizePlayerName(Object value) {
		String output = value == null ? "" : String.valueOf(value).trim();
		return output.isBlank() ? "Unknown" : output;
	}

	private String normalizeDiscordId(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private String normalizeDiscordUsername(Object value) {
		String output = value == null ? "" : String.valueOf(value).trim();
		return output.isBlank() ? "Unknown" : output;
	}

	private long longValue(Object value) {
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

	private record PendingLinkCode(String code, UUID playerId, String playerName, long expiresAtEpochMs) {
	}

	public record LinkedAccount(
		UUID playerId,
		String playerName,
		String discordUserId,
		String discordUsername,
		long linkedAtEpochMs,
		long updatedAtEpochMs
	) {
	}

	public record BeginLinkResult(boolean success, boolean databaseDisabled, String code, long expiresAtEpochMs) {
		public static BeginLinkResult success(String code, long expiresAtEpochMs) {
			return new BeginLinkResult(true, false, code, expiresAtEpochMs);
		}

		public static BeginLinkResult ofDatabaseDisabled() {
			return new BeginLinkResult(false, true, "", 0L);
		}
	}

	public record LinkByCodeResult(
		boolean success,
		boolean databaseDisabled,
		boolean invalidCode,
		boolean expiredCode,
		boolean discordAlreadyLinked,
		LinkedAccount account,
		LinkedAccount existingDiscordAccount
	) {
		public static LinkByCodeResult success(LinkedAccount account) {
			return new LinkByCodeResult(true, false, false, false, false, account, null);
		}

		public static LinkByCodeResult ofDatabaseDisabled() {
			return new LinkByCodeResult(false, true, false, false, false, null, null);
		}

		public static LinkByCodeResult ofInvalidCode() {
			return new LinkByCodeResult(false, false, true, false, false, null, null);
		}

		public static LinkByCodeResult ofExpiredCode() {
			return new LinkByCodeResult(false, false, false, true, false, null, null);
		}

		public static LinkByCodeResult discordAlreadyLinked(LinkedAccount existing) {
			return new LinkByCodeResult(false, false, false, false, true, null, existing);
		}
	}

	public record UnlinkResult(boolean success, boolean databaseDisabled, boolean notLinked, LinkedAccount account) {
		public static UnlinkResult success(LinkedAccount account) {
			return new UnlinkResult(true, false, false, account);
		}

		public static UnlinkResult ofNotLinked() {
			return new UnlinkResult(false, false, true, null);
		}

		public static UnlinkResult ofDatabaseDisabled() {
			return new UnlinkResult(false, true, false, null);
		}
	}
}
