package dev.belikhun.luna.vault.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.model.VaultAccountModel;
import dev.belikhun.luna.vault.api.model.VaultAccountRepository;
import dev.belikhun.luna.vault.api.model.VaultTransactionModel;
import dev.belikhun.luna.vault.api.model.VaultTransactionRepository;

import java.time.Instant;
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
	private final VaultAccountRepository accountRepository;
	private final VaultTransactionRepository transactionRepository;

	public VelocityVaultService(ProxyServer proxyServer, Database database, LunaLogger logger, VelocityVaultConfig config) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("Service");
		this.databaseEnabled = !(database instanceof NoopDatabase);
		this.config = config;
		this.accountRepository = new VaultAccountRepository(database);
		this.transactionRepository = new VaultTransactionRepository(database);
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		if (!databaseEnabled || playerId == null) {
			return CompletableFuture.completedFuture(0L);
		}

		return CompletableFuture.completedFuture(balanceNow(playerId, playerName));
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
		return accountRepository.balance(playerId, resolveName(playerId, playerName));
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
		}
		return VaultOperationResult.success("Đã cập nhật số dư.", newBalanceMinor, transaction);
	}

	private void touchAccount(VaultAccountModel account, String playerName, long newBalance) {
		long now = Instant.now().toEpochMilli();
		account
			.set("player_name", playerName == null ? "" : playerName)
			.set("balance_minor", newBalance)
			.set("updated_at", now)
			.save();
	}

	private VaultTransactionRecord recordTransaction(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		long completedAt = Instant.now().toEpochMilli();
		String normalizedSource = source == null || source.isBlank() ? "lunavault" : source;
		String transactionId = UUID.randomUUID().toString();
		VaultTransactionModel model = transactionRepository.newModel();
		model
			.set("transaction_id", transactionId)
			.set("sender_uuid", senderId == null ? null : senderId.toString())
			.set("sender_name", senderName)
			.set("receiver_uuid", receiverId == null ? null : receiverId.toString())
			.set("receiver_name", receiverName)
			.set("amount_minor", amountMinor)
			.set("source_plugin", normalizedSource)
			.set("details", details)
			.set("completed_at", completedAt)
			.save();
		VaultTransactionRecord transaction = new VaultTransactionRecord(transactionId, senderId, senderName, receiverId, receiverName, amountMinor, normalizedSource, details, completedAt);
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
			+ "</white> <gold>" + VaultMoney.formatDefault(transaction.amountMinor()) + "</gold> → <white>"
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
			.append(VaultMoney.formatDefault(transaction.amountMinor()))
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
			return providedName;
		}

		Optional<Player> player = proxyServer.getPlayer(playerId);
		if (player.isPresent()) {
			return player.get().getUsername();
		}

		return accountRepository.find(playerId)
			.map(model -> model.getString("player_name", playerId.toString()))
			.orElse(playerId.toString());
	}

	public record AccountTarget(UUID playerId, String playerName) {
	}
}
