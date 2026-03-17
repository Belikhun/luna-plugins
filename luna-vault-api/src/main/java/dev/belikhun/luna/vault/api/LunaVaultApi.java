package dev.belikhun.luna.vault.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface LunaVaultApi {
	CompletableFuture<Long> balance(UUID playerId, String playerName);

	default CompletableFuture<Boolean> has(UUID playerId, String playerName, long amountMinor) {
		return balance(playerId, playerName)
			.thenApply(balanceMinor -> balanceMinor >= Math.max(0L, amountMinor));
	}

	CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details);

	CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details);

	CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details);

	CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details);

	CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize);
}
