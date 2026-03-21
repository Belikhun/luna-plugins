package dev.belikhun.luna.vault.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.profile.UserProfileRepository;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.VaultCacheRefresh;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.model.VaultAccountModel;
import dev.belikhun.luna.vault.api.model.VaultAccountRepository;
import dev.belikhun.luna.vault.api.model.VaultTransactionModel;
import dev.belikhun.luna.vault.api.model.VaultTransactionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class VelocityVaultService implements LunaVaultApi {
	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final boolean databaseEnabled;
	private final VelocityVaultConfig config;
	private final PluginMessageBus<Object, Object> pluginMessagingBus;
	private final UserProfileRepository userProfileRepository;
	private final VaultAccountRepository accountRepository;
	private final VaultTransactionRepository transactionRepository;
	private final LegacyBalanceImportService legacyBalanceImportService;

	public VelocityVaultService(ProxyServer proxyServer, Database database, LunaLogger logger, VelocityVaultConfig config, PluginMessageBus<Object, Object> pluginMessagingBus) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("Service");
		this.databaseEnabled = !(database instanceof NoopDatabase);
		this.config = config;
		this.pluginMessagingBus = pluginMessagingBus;
		this.userProfileRepository = new UserProfileRepository(database);
		this.accountRepository = new VaultAccountRepository(database);
		this.transactionRepository = new VaultTransactionRepository(database);
		this.legacyBalanceImportService = new LegacyBalanceImportService(logger, accountRepository, userProfileRepository);
	}

	public LegacyBalanceImportService legacyBalanceImportService() {
		return legacyBalanceImportService;
	}

	@Override
	public CompletableFuture<VaultPlayerSnapshot> snapshot(UUID playerId, String playerName) {
		if (!databaseEnabled || playerId == null) {
			return CompletableFuture.completedFuture(VaultPlayerSnapshot.empty(playerId, playerName));
		}

		return CompletableFuture.completedFuture(snapshotNow(playerId, playerName));
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		if (!databaseEnabled || playerId == null) {
			return CompletableFuture.completedFuture(0L);
		}

		return CompletableFuture.completedFuture(balanceNow(playerId, playerName));
	}

	@Override
	public CompletableFuture<VaultLeaderboardPage> leaderboard(int page, int pageSize) {
		if (!databaseEnabled) {
			return CompletableFuture.completedFuture(VaultLeaderboardPage.empty(page, pageSize));
		}

		return CompletableFuture.completedFuture(accountRepository.leaderboard(page, pageSize));
	}

	public void invalidateBackendCaches() {
		broadcastCacheRefresh(true, List.of());
	}

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return CompletableFuture.completedFuture(depositNow(actorId, actorName, playerId, playerName, amountMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return CompletableFuture.completedFuture(withdrawNow(actorId, actorName, playerId, playerName, amountMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		return CompletableFuture.completedFuture(transferNow(senderId, senderName, receiverId, receiverName, amountMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		return CompletableFuture.completedFuture(setBalanceNow(actorId, actorName, playerId, playerName, newBalanceMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize) {
		if (!databaseEnabled || playerId == null) {
			return CompletableFuture.completedFuture(VaultTransactionPage.empty(page, pageSize));
		}

		return CompletableFuture.completedFuture(transactionRepository.pageForPlayer(playerId, page, pageSize));
	}

	public Optional<Player> findOnlinePlayer(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}

		return proxyServer.getAllPlayers().stream()
			.filter(player -> player.getUsername().equalsIgnoreCase(username))
			.findFirst();
	}

	public Optional<AccountTarget> resolveTarget(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}

		Optional<Player> online = findOnlinePlayer(username);
		if (online.isPresent()) {
			Player player = online.get();
			accountRepository.find(player.getUniqueId()).ifPresent(account -> refreshKnownName(account, player.getUsername()));
			return Optional.of(new AccountTarget(player.getUniqueId(), player.getUsername()));
		}

		return accountRepository.findByName(username)
			.map(model -> new AccountTarget(
				UUID.fromString(model.getString("player_uuid", "")),
				model.getString("player_name", username)
			));
	}

	public List<String> suggestTargets(String partial) {
		String token = partial == null ? "" : partial.trim();
		LinkedHashSet<String> suggestions = new LinkedHashSet<>();
		proxyServer.getAllPlayers().stream()
			.map(Player::getUsername)
			.filter(name -> token.isBlank() || name.regionMatches(true, 0, token, 0, token.length()))
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.limit(20)
			.forEach(suggestions::add);
		accountRepository.searchNamesByPrefix(token, 20).forEach(suggestions::add);
		return suggestions.stream().limit(20).toList();
	}

	private synchronized long balanceNow(UUID playerId, String playerName) {
		return snapshotNow(playerId, playerName).balanceMinor();
	}

	private synchronized VaultPlayerSnapshot snapshotNow(UUID playerId, String playerName) {
		String resolvedName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedName);
		refreshKnownName(account, resolvedName);
		return accountRepository.snapshot(playerId)
			.orElse(new VaultPlayerSnapshot(
				playerId,
				VaultAccountRepository.normalizePlayerName(account.getString("player_name", resolvedName)),
				account.getLong("balance_minor", 0L),
				0
			));
	}

	private synchronized VaultOperationResult depositNow(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (!databaseEnabled) {
			return VaultOperationResult.failed(VaultFailureReason.DATABASE_DISABLED, "Database của LunaVault chưa sẵn sàng.", 0L);
		}
		if (playerId == null || amountMinor <= 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số tiền không hợp lệ.", 0L);
		}

		String resolvedPlayerName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedPlayerName);
		long newBalance = account.getLong("balance_minor", 0L) + amountMinor;
		touchAccount(account, resolvedPlayerName, newBalance);
		VaultTransactionRecord transaction = recordTransaction(actorId, actorName, playerId, resolvedPlayerName, amountMinor, source, details);
		VaultPlayerSnapshot snapshot = snapshotNow(playerId, resolvedPlayerName);
		broadcastCacheRefresh(true, List.of(snapshot));
		return VaultOperationResult.success("Đã cộng tiền thành công.", newBalance, transaction);
	}

	private synchronized VaultOperationResult withdrawNow(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (!databaseEnabled) {
			return VaultOperationResult.failed(VaultFailureReason.DATABASE_DISABLED, "Database của LunaVault chưa sẵn sàng.", 0L);
		}
		if (playerId == null || amountMinor <= 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số tiền không hợp lệ.", 0L);
		}

		String resolvedPlayerName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedPlayerName);
		long currentBalance = account.getLong("balance_minor", 0L);
		if (currentBalance < amountMinor) {
			return VaultOperationResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, "Số dư không đủ.", currentBalance);
		}

		long newBalance = currentBalance - amountMinor;
		touchAccount(account, resolvedPlayerName, newBalance);
		VaultTransactionRecord transaction = recordTransaction(playerId, resolvedPlayerName, actorId, actorName, amountMinor, source, details);
		VaultPlayerSnapshot snapshot = snapshotNow(playerId, resolvedPlayerName);
		broadcastCacheRefresh(true, List.of(snapshot));
		return VaultOperationResult.success("Đã trừ tiền thành công.", newBalance, transaction);
	}

	private synchronized VaultOperationResult transferNow(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		if (!databaseEnabled) {
			return VaultOperationResult.failed(VaultFailureReason.DATABASE_DISABLED, "Database của LunaVault chưa sẵn sàng.", 0L);
		}
		if (senderId == null || receiverId == null || amountMinor <= 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số tiền hoặc người chơi không hợp lệ.", 0L);
		}
		if (senderId.equals(receiverId)) {
			return VaultOperationResult.failed(VaultFailureReason.SELF_TRANSFER, "Không thể chuyển tiền cho chính mình.", balanceNow(senderId, senderName));
		}

		String resolvedSenderName = resolveName(senderId, senderName);
		String resolvedReceiverName = resolveName(receiverId, receiverName);
		VaultAccountModel senderAccount = accountRepository.findOrCreate(senderId, resolvedSenderName);
		VaultAccountModel receiverAccount = accountRepository.findOrCreate(receiverId, resolvedReceiverName);
		long senderBalance = senderAccount.getLong("balance_minor", 0L);
		if (senderBalance < amountMinor) {
			return VaultOperationResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, "Số dư không đủ để chuyển tiền.", senderBalance);
		}

		touchAccount(senderAccount, resolvedSenderName, senderBalance - amountMinor);
		touchAccount(receiverAccount, resolvedReceiverName, receiverAccount.getLong("balance_minor", 0L) + amountMinor);
		VaultTransactionRecord transaction = recordTransaction(senderId, resolvedSenderName, receiverId, resolvedReceiverName, amountMinor, source, details);
		VaultPlayerSnapshot senderSnapshot = snapshotNow(senderId, resolvedSenderName);
		VaultPlayerSnapshot receiverSnapshot = snapshotNow(receiverId, resolvedReceiverName);
		broadcastCacheRefresh(true, List.of(senderSnapshot, receiverSnapshot));
		return VaultOperationResult.success("Đã chuyển tiền thành công.", senderBalance - amountMinor, transaction);
	}

	private synchronized VaultOperationResult setBalanceNow(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		if (!databaseEnabled) {
			return VaultOperationResult.failed(VaultFailureReason.DATABASE_DISABLED, "Database của LunaVault chưa sẵn sàng.", 0L);
		}
		if (playerId == null || newBalanceMinor < 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số dư mới không hợp lệ.", 0L);
		}

		String resolvedPlayerName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedPlayerName);
		long oldBalance = account.getLong("balance_minor", 0L);
		touchAccount(account, resolvedPlayerName, newBalanceMinor);
		long delta = Math.abs(newBalanceMinor - oldBalance);
		VaultTransactionRecord transaction = null;
		if (delta > 0L) {
			UUID senderId = newBalanceMinor >= oldBalance ? actorId : playerId;
			String senderName = newBalanceMinor >= oldBalance ? actorName : resolvedPlayerName;
			UUID receiverId = newBalanceMinor >= oldBalance ? playerId : actorId;
			String receiverName = newBalanceMinor >= oldBalance ? resolvedPlayerName : actorName;
			transaction = recordTransaction(senderId, senderName, receiverId, receiverName, delta, source, details);
			broadcastCacheRefresh(true, List.of(snapshotNow(playerId, resolvedPlayerName)));
		}
		return VaultOperationResult.success("Đã cập nhật số dư.", newBalanceMinor, transaction);
	}

	private void touchAccount(VaultAccountModel account, String playerName, long newBalance) {
		long now = Instant.now().toEpochMilli();
		account
			.set("player_name", VaultAccountRepository.normalizePlayerName(playerName))
			.set("balance_minor", newBalance)
			.set("updated_at", now)
			.save();
	}

	private void refreshKnownName(VaultAccountModel account, String playerName) {
		String normalizedName = VaultAccountRepository.normalizePlayerName(playerName);
		if (normalizedName.isBlank()) {
			return;
		}

		String currentName = VaultAccountRepository.normalizePlayerName(account.getString("player_name", ""));
		if (normalizedName.equals(currentName)) {
			return;
		}

		account
			.set("player_name", normalizedName)
			.set("updated_at", Instant.now().toEpochMilli())
			.save();
	}

	private VaultTransactionRecord recordTransaction(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		long completedAt = Instant.now().toEpochMilli();
		String normalizedSource = source == null || source.isBlank() ? "lunavault" : source;
		String transactionId = UUID.randomUUID().toString();
		VaultTransactionModel model = transactionRepository.newModel();
		String normalizedSenderName = nullableStoredName(senderName);
		String normalizedReceiverName = nullableStoredName(receiverName);
		model
			.set("transaction_id", transactionId)
			.set("sender_uuid", senderId == null ? null : senderId.toString())
			.set("sender_name", normalizedSenderName)
			.set("receiver_uuid", receiverId == null ? null : receiverId.toString())
			.set("receiver_name", normalizedReceiverName)
			.set("amount_minor", amountMinor)
			.set("source_plugin", normalizedSource)
			.set("details", details)
			.set("completed_at", completedAt)
			.save();
		VaultTransactionRecord transaction = new VaultTransactionRecord(transactionId, senderId, normalizedSenderName, receiverId, normalizedReceiverName, amountMinor, normalizedSource, details, completedAt);
		announceTransaction(transaction);
		return transaction;
	}

	private void announceTransaction(VaultTransactionRecord transaction) {
		if (config.transactionLoggingEnabled()) {
			logger.audit(formatTransactionAudit(transaction));
		}

		VelocityVaultConfig.LargeTransactionAlertConfig alertConfig = config.largeTransactionAlert();
		if (!alertConfig.enabled() || transaction.amountMinor() < alertConfig.thresholdMinor()) {
			return;
		}

		String message = "<yellow>⚠ Giao dịch lớn: <white>" + describeActor(transaction.senderName(), transaction.senderId())
			+ "</white> " + LunaCoreVelocity.services().moneyFormat().formatMinor(transaction.amountMinor(), VaultMoney.SCALE) + " <white>"
			+ describeActor(transaction.receiverName(), transaction.receiverId()) + "</white> <gray>(nguồn: "
			+ transaction.source() + ")</gray></yellow>";
		proxyServer.getAllPlayers().stream()
			.filter(player -> player.hasPermission(alertConfig.permission()))
			.forEach(player -> player.sendRichMessage(message));
		logger.warn(stripMiniMessage(message));
	}

	private String formatTransactionAudit(VaultTransactionRecord transaction) {
		StringBuilder builder = new StringBuilder("TX ")
			.append(transaction.transactionId())
			.append(" | ")
			.append(describeActor(transaction.senderName(), transaction.senderId()))
			.append(" -> ")
			.append(describeActor(transaction.receiverName(), transaction.receiverId()))
			.append(" | amount=")
			.append(stripMiniMessage(LunaCoreVelocity.services().moneyFormat().formatMinor(transaction.amountMinor(), VaultMoney.SCALE)))
			.append(" | source=")
			.append(transaction.source());
		if (transaction.details() != null && !transaction.details().isBlank()) {
			builder.append(" | details=").append(transaction.details());
		}
		return builder.toString();
	}

	private String describeActor(String name, UUID playerId) {
		if (name != null && !name.isBlank()) {
			return name;
		}
		return playerId == null ? "HỆ THỐNG" : playerId.toString();
	}

	private String stripMiniMessage(String input) {
		return input.replaceAll("<[^>]+>", "");
	}

	private String resolveName(UUID playerId, String providedName) {
		if (providedName != null && !providedName.isBlank()) {
			return VaultAccountRepository.normalizePlayerName(providedName);
		}

		Optional<Player> player = proxyServer.getPlayer(playerId);
		if (player.isPresent()) {
			return VaultAccountRepository.normalizePlayerName(player.get().getUsername());
		}

		return accountRepository.find(playerId)
			.map(model -> VaultAccountRepository.normalizePlayerName(model.getString("player_name", "")))
			.filter(name -> !name.isBlank())
			.or(() -> userProfileRepository.findByUuid(playerId)
				.map(profile -> VaultAccountRepository.normalizePlayerName(profile.name())))
			.orElse("");
	}

	private String nullableStoredName(String playerName) {
		String normalized = VaultAccountRepository.normalizePlayerName(playerName);
		return normalized.isBlank() ? null : normalized;
	}

	private void broadcastCacheRefresh(boolean clearAll, Collection<VaultPlayerSnapshot> snapshots) {
		if (pluginMessagingBus == null) {
			return;
		}

		List<VaultPlayerSnapshot> payloadSnapshots = uniqueSnapshots(snapshots);
		if (!clearAll && payloadSnapshots.isEmpty()) {
			return;
		}

		VaultCacheRefresh refresh = new VaultCacheRefresh(clearAll, payloadSnapshots);
		for (RegisteredServer server : targetServersForRefresh(clearAll, payloadSnapshots)) {
			pluginMessagingBus.send(server, VaultChannels.CACHE_SYNC, writer -> {
				writer.writeUtf("refresh");
				refresh.writeTo(writer);
			});
		}
	}

	private Collection<RegisteredServer> targetServersForRefresh(boolean clearAll, List<VaultPlayerSnapshot> payloadSnapshots) {
		if (clearAll) {
			return proxyServer.getAllServers();
		}

		LinkedHashSet<RegisteredServer> targets = new LinkedHashSet<>();
		for (VaultPlayerSnapshot snapshot : payloadSnapshots) {
			if (snapshot == null || snapshot.playerId() == null) {
				continue;
			}

			proxyServer.getPlayer(snapshot.playerId())
				.flatMap(Player::getCurrentServer)
				.ifPresent(connection -> targets.add(connection.getServer()));
		}

		if (!targets.isEmpty()) {
			return targets;
		}

		return proxyServer.getAllServers();
	}

	private List<VaultPlayerSnapshot> uniqueSnapshots(Collection<VaultPlayerSnapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			return List.of();
		}

		LinkedHashSet<UUID> seen = new LinkedHashSet<>();
		List<VaultPlayerSnapshot> values = new ArrayList<>();
		for (VaultPlayerSnapshot snapshot : snapshots) {
			if (snapshot == null || snapshot.playerId() == null || !seen.add(snapshot.playerId())) {
				continue;
			}
			values.add(snapshot);
		}
		return values;
	}

	public record AccountTarget(UUID playerId, String playerName) {
	}
}
