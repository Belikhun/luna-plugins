package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.LuckPermsService;

import com.velocitypowered.api.proxy.ProxyServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DiscordAccountLinkService {
	private static final String LINKS_TABLE = "messenger_discord_links";
	private static final String GROUP_HISTORY_TABLE = "messenger_discord_link_group_history";
	private static final String BYPASS_TABLE = "messenger_discord_link_bypass";
	private static final String USER_PROFILES_TABLE = "user_profiles";

	private final LunaLogger logger;
	private final Database database;
	private final boolean databaseEnabled;
	private final long codeTtlMs;
	private final ProxyServer proxyServer;
	private final LuckPermsService luckPermsService;
	private final String linkGroup;
	private final String unlinkGroup;
	private final List<String> linkActions;
	private final List<String> unlinkActions;
	private final java.util.Set<UUID> serverProtectionBypassPlayers;
	private final ConcurrentMap<String, PendingLinkCode> pendingByCode;
	private final ConcurrentMap<UUID, PendingLinkCode> pendingByPlayer;

	private DiscordAccountLinkService(
		LunaLogger logger,
		Database database,
		boolean databaseEnabled,
		long codeTtlMs,
		ProxyServer proxyServer,
		LuckPermsService luckPermsService,
		String linkGroup,
		String unlinkGroup,
		List<String> linkActions,
		List<String> unlinkActions
	) {
		this.logger = logger.scope("DiscordLinkService");
		this.database = database;
		this.databaseEnabled = databaseEnabled;
		this.codeTtlMs = Math.max(30_000L, codeTtlMs);
		this.proxyServer = proxyServer;
		this.luckPermsService = luckPermsService == null ? new LuckPermsService() : luckPermsService;
		this.linkGroup = normalizeGroupName(linkGroup);
		this.unlinkGroup = normalizeGroupName(unlinkGroup);
		this.linkActions = List.copyOf(linkActions);
		this.unlinkActions = List.copyOf(unlinkActions);
		this.serverProtectionBypassPlayers = ConcurrentHashMap.newKeySet();
		this.pendingByCode = new ConcurrentHashMap<>();
		this.pendingByPlayer = new ConcurrentHashMap<>();
	}

	public static DiscordAccountLinkService create(
		Database sharedDatabase,
		java.nio.file.Path configPath,
		ProxyServer proxyServer,
		LuckPermsService luckPermsService,
		LunaLogger logger
	) {
		Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> discordLink = ConfigValues.map(root, "discord-link");
		long codeTtlMs = ConfigValues.intValue(discordLink, "code-expiration-seconds", 300) * 1000L;
		Map<String, Object> groups = ConfigValues.map(discordLink, "groups");
		String linkGroup = ConfigValues.string(groups, "on-link", "");
		String unlinkGroup = ConfigValues.string(groups, "on-unlink", "");
		Map<String, Object> actions = ConfigValues.map(discordLink, "actions");
		List<String> linkActions = stringList(actions.get("on-link"));
		List<String> unlinkActions = stringList(actions.get("on-unlink"));

		if (sharedDatabase == null || sharedDatabase instanceof NoopDatabase) {
			logger.warn("LunaCore database đang tắt hoặc chưa sẵn sàng. Link Discord sẽ không hoạt động.");
			return new DiscordAccountLinkService(
				logger,
				new NoopDatabase(),
				false,
				codeTtlMs,
				proxyServer,
				luckPermsService,
				linkGroup,
				unlinkGroup,
				linkActions,
				unlinkActions
			);
		}

		try {
			DatabaseMigrator migrator = new DatabaseMigrator(sharedDatabase, logger.scope("DiscordLinkMigration"));
			DiscordLinkDatabaseMigrations.register(migrator);
			migrator.migrateNamespace("lunamessenger");
			logger.success("Đã gắn Discord link vào database instance của LunaCore.");
			DiscordAccountLinkService service = new DiscordAccountLinkService(
				logger,
				sharedDatabase,
				true,
				codeTtlMs,
				proxyServer,
				luckPermsService,
				linkGroup,
				unlinkGroup,
				linkActions,
				unlinkActions
			);
			service.loadPersistentBypasses();
			return service;
		} catch (Exception exception) {
			logger.error("Không thể khởi tạo bảng/migration cho Discord link trên LunaCore database.", exception);
			return new DiscordAccountLinkService(
				logger,
				new NoopDatabase(),
				false,
				codeTtlMs,
				proxyServer,
				luckPermsService,
				linkGroup,
				unlinkGroup,
				linkActions,
				unlinkActions
			);
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

		String storedGroup = consumeStoredPreviousGroup(account.playerId());
		String appliedGroup = storedGroup.isBlank() ? linkGroup : storedGroup;
		applyLinkGroup(account, appliedGroup, storedGroup);
		runConfiguredActions(linkActions, account, appliedGroup, storedGroup);
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

		String previousGroup = resolvePrimaryGroupName(existing.get().playerId());
		storePreviousGroup(existing.get().playerId(), previousGroup);

		database.update("DELETE FROM " + LINKS_TABLE + " WHERE minecraft_uuid = ?", List.of(playerId.toString()));
		clearPendingByPlayer(playerId);
		applyUnlinkGroup(existing.get());
		runConfiguredActions(unlinkActions, existing.get(), unlinkGroup, previousGroup);
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

		String previousGroup = resolvePrimaryGroupName(existing.get().playerId());
		storePreviousGroup(existing.get().playerId(), previousGroup);

		database.update("DELETE FROM " + LINKS_TABLE + " WHERE discord_user_id = ?", List.of(discordUserId));
		clearPendingByPlayer(existing.get().playerId());
		applyUnlinkGroup(existing.get());
		runConfiguredActions(unlinkActions, existing.get(), unlinkGroup, previousGroup);
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

	public boolean grantServerProtectionBypass(UUID playerId) {
		if (playerId == null) {
			return false;
		}
		return grantServerProtectionBypass(new PlayerIdentity(playerId, "Unknown"));
	}

	public boolean grantServerProtectionBypass(PlayerIdentity identity) {
		if (identity == null || identity.playerId() == null) {
			return false;
		}

		UUID playerId = identity.playerId();
		String playerName = normalizePlayerName(identity.playerName());
		boolean added = serverProtectionBypassPlayers.add(playerId);
		if (databaseEnabled) {
			long now = System.currentTimeMillis();
			int updated = database.update(
				"UPDATE " + BYPASS_TABLE + " SET minecraft_name = ?, updated_at = ? WHERE minecraft_uuid = ?",
				List.of(playerName, now, playerId.toString())
			);
			if (updated == 0) {
				database.update(
					"INSERT INTO " + BYPASS_TABLE + " (minecraft_uuid, minecraft_name, updated_at) VALUES (?, ?, ?)",
					List.of(playerId.toString(), playerName, now)
				);
			}
		}
		return added;
	}

	public boolean revokeServerProtectionBypass(UUID playerId) {
		if (playerId == null) {
			return false;
		}

		boolean removed = serverProtectionBypassPlayers.remove(playerId);
		if (databaseEnabled) {
			int deleted = database.update(
				"DELETE FROM " + BYPASS_TABLE + " WHERE minecraft_uuid = ?",
				List.of(playerId.toString())
			);
			removed = removed || deleted > 0;
		}
		return removed;
	}

	public boolean isServerProtectionBypassed(UUID playerId) {
		if (playerId == null) {
			return false;
		}
		return serverProtectionBypassPlayers.contains(playerId);
	}

	public Optional<PlayerIdentity> resolvePlayerIdentity(String minecraftName) {
		if (minecraftName == null || minecraftName.isBlank()) {
			return Optional.empty();
		}

		String queryName = minecraftName.trim();
		if (proxyServer != null) {
			Optional<PlayerIdentity> online = proxyServer.getAllPlayers().stream()
				.filter(player -> player.getUsername().equalsIgnoreCase(queryName))
				.findFirst()
				.map(player -> new PlayerIdentity(player.getUniqueId(), player.getUsername()));
			if (online.isPresent()) {
				return online;
			}
		}

		if (!databaseEnabled) {
			return Optional.empty();
		}

		Optional<PlayerIdentity> profileIdentity = database.first(
			"SELECT uuid, name FROM " + USER_PROFILES_TABLE + " WHERE LOWER(name) = LOWER(?) LIMIT 1",
			List.of(queryName)
		).flatMap(row -> toPlayerIdentity(row.get("uuid"), row.get("name")));
		if (profileIdentity.isPresent()) {
			return profileIdentity;
		}

		return database.first(
			"SELECT minecraft_uuid, minecraft_name FROM " + LINKS_TABLE + " WHERE LOWER(minecraft_name) = LOWER(?) LIMIT 1",
			List.of(queryName)
		).flatMap(row -> toPlayerIdentity(row.get("minecraft_uuid"), row.get("minecraft_name")));
	}

	public List<PlayerIdentity> listServerProtectionBypasses() {
		if (!databaseEnabled) {
			return serverProtectionBypassPlayers.stream()
				.map(uuid -> new PlayerIdentity(uuid, "Unknown"))
				.sorted((a, b) -> a.playerId().toString().compareToIgnoreCase(b.playerId().toString()))
				.toList();
		}

		List<PlayerIdentity> output = new ArrayList<>();
		for (Map<String, Object> row : database.query(
			"SELECT minecraft_uuid, minecraft_name FROM " + BYPASS_TABLE + " ORDER BY updated_at DESC",
			List.of()
		)) {
			toPlayerIdentity(row.get("minecraft_uuid"), row.get("minecraft_name")).ifPresent(output::add);
		}
		return output;
	}

	public void close() {
		serverProtectionBypassPlayers.clear();
		pendingByCode.clear();
		pendingByPlayer.clear();
	}

	private Optional<PlayerIdentity> toPlayerIdentity(Object uuidValue, Object nameValue) {
		String rawUuid = uuidValue == null ? "" : String.valueOf(uuidValue).trim();
		if (rawUuid.isBlank()) {
			return Optional.empty();
		}

		try {
			UUID uuid = UUID.fromString(rawUuid);
			String name = normalizePlayerName(nameValue);
			return Optional.of(new PlayerIdentity(uuid, name));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private void loadPersistentBypasses() {
		if (!databaseEnabled) {
			return;
		}

		serverProtectionBypassPlayers.clear();
		for (Map<String, Object> row : database.query(
			"SELECT minecraft_uuid FROM " + BYPASS_TABLE,
			List.of()
		)) {
			Object value = row.get("minecraft_uuid");
			if (value == null) {
				continue;
			}
			try {
				serverProtectionBypassPlayers.add(UUID.fromString(String.valueOf(value).trim()));
			} catch (IllegalArgumentException ignored) {
			}
		}
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

	private void applyLinkGroup(LinkedAccount account, String groupName, String previousGroup) {
		if (groupName.isBlank()) {
			return;
		}

		boolean applied = luckPermsService.setUserPrimaryGroup(account.playerId(), groupName);
		if (!applied) {
			logger.warn("Không thể đặt nhóm LuckPerms khi link cho " + account.playerName() + " -> " + groupName);
		}
		if (!previousGroup.isBlank()) {
			logger.audit("Khôi phục nhóm LuckPerms trước đó cho " + account.playerName() + ": " + previousGroup);
		}
	}

	private void applyUnlinkGroup(LinkedAccount account) {
		if (unlinkGroup.isBlank()) {
			boolean cleared = luckPermsService.clearUserPrimaryGroup(account.playerId());
			if (!cleared) {
				logger.warn("Không thể xóa nhóm LuckPerms khi unlink cho " + account.playerName());
			}
			return;
		}

		boolean applied = luckPermsService.setUserPrimaryGroup(account.playerId(), unlinkGroup);
		if (!applied) {
			logger.warn("Không thể đặt nhóm LuckPerms khi unlink cho " + account.playerName() + " -> " + unlinkGroup);
		}
	}

	private String resolvePrimaryGroupName(UUID playerId) {
		if (playerId == null) {
			return "";
		}
		return normalizeGroupName(luckPermsService.getGroupName(playerId));
	}

	private void storePreviousGroup(UUID playerId, String previousGroup) {
		if (!databaseEnabled || playerId == null) {
			return;
		}

		String normalized = normalizeGroupName(previousGroup);
		long now = System.currentTimeMillis();
		int updated = database.update(
			"UPDATE " + GROUP_HISTORY_TABLE + " SET previous_group = ?, updated_at = ? WHERE minecraft_uuid = ?",
			List.of(normalized, now, playerId.toString())
		);
		if (updated == 0) {
			database.update(
				"INSERT INTO " + GROUP_HISTORY_TABLE + " (minecraft_uuid, previous_group, updated_at) VALUES (?, ?, ?)",
				List.of(playerId.toString(), normalized, now)
			);
		}
	}

	private String consumeStoredPreviousGroup(UUID playerId) {
		if (!databaseEnabled || playerId == null) {
			return "";
		}

		String group = database.first(
			"SELECT previous_group FROM " + GROUP_HISTORY_TABLE + " WHERE minecraft_uuid = ?",
			List.of(playerId.toString())
		).map(row -> normalizeGroupName(row.get("previous_group"))).orElse("");
		database.update(
			"DELETE FROM " + GROUP_HISTORY_TABLE + " WHERE minecraft_uuid = ?",
			List.of(playerId.toString())
		);
		return group;
	}

	private void runConfiguredActions(List<String> commands, LinkedAccount account, String group, String previousGroup) {
		if (commands == null || commands.isEmpty()) {
			return;
		}

		Map<String, String> placeholders = Map.of(
			"%player_uuid%", account.playerId().toString(),
			"%player_name%", account.playerName(),
			"%discord_id%", account.discordUserId(),
			"%discord_username%", account.discordUsername(),
			"%group%", group == null ? "" : group,
			"%previous_group%", previousGroup == null ? "" : previousGroup
		);

		for (String commandTemplate : commands) {
			String command = applyPlaceholders(commandTemplate, placeholders).trim();
			if (command.isEmpty()) {
				continue;
			}
			runConsoleCommand(command, "Không thể chạy action command: " + command);
		}
	}

	private String applyPlaceholders(String template, Map<String, String> placeholders) {
		if (template == null || template.isBlank()) {
			return "";
		}

		String output = template;
		for (Map.Entry<String, String> entry : placeholders.entrySet()) {
			output = output.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
		}
		return output;
	}

	private void runConsoleCommand(String command, String failureMessage) {
		if (proxyServer == null || command == null || command.isBlank()) {
			return;
		}

		proxyServer.getCommandManager()
			.executeAsync(proxyServer.getConsoleCommandSource(), command)
			.exceptionally(throwable -> {
				logger.warn(failureMessage + ": " + throwable.getMessage());
				return null;
			});
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> source)) {
			return List.of();
		}

		List<String> output = new ArrayList<>();
		for (Object item : source) {
			if (item == null) {
				continue;
			}
			String text = String.valueOf(item).trim();
			if (!text.isEmpty()) {
				output.add(text);
			}
		}
		return output;
	}

	private String normalizeGroupName(Object value) {
		if (value == null) {
			return "";
		}
		return String.valueOf(value).trim().toLowerCase();
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

	public record PlayerIdentity(
		UUID playerId,
		String playerName
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
