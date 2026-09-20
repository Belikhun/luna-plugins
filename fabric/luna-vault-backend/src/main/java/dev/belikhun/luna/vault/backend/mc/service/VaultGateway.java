package dev.belikhun.luna.vault.backend.mc.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.client.VaultClient;
import dev.belikhun.luna.vault.api.client.VaultPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * This backend's half of the network economy, on a mod loader.
 *
 * The proxy owns the money; this is the shared {@link VaultClient} bound to
 * the vanilla player list. Join and quit are calls the loader's own bootstrap
 * makes, which is why this class serves the fabric, neoforge and forge builds
 * unchanged: everything under {@code net.minecraft} here compiles identically
 * on all three.
 */
public final class VaultGateway implements LunaVaultApi, VaultPlatform<ServerPlayer> {
	private final MinecraftServer server;
	private final VaultClient<ServerPlayer> client;

	public VaultGateway(
		MinecraftServer server,
		String backendName,
		LunaLogger logger,
		PluginMessageBus<ServerPlayer, ServerPlayer> bus,
		long requestTimeoutMillis
	) {
		this.server = server;
		this.client = new VaultClient<>(this, bus, logger.scope("Gateway"), backendName, requestTimeoutMillis);
	}

	// -------------------------------------------------------------- platform

	@Override
	public UUID idOf(ServerPlayer player) {
		return player.getUUID();
	}

	@Override
	public String nameOf(ServerPlayer player) {
		return player.getName().getString();
	}

	@Override
	public ServerPlayer byId(UUID playerId) {
		return server.getPlayerList().getPlayer(playerId);
	}

	@Override
	public Collection<? extends ServerPlayer> online() {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		return players == null ? List.of() : players;
	}

	// ---------------------------------------------------------------- events

	/** A player arriving: fetch their balance now, so the first read is not zero. */
	public void onPlayerJoin(ServerPlayer player) {
		client.onPlayerJoin(player);
	}

	public void onPlayerQuit(ServerPlayer player) {
		client.onPlayerQuit(player);
	}

	// ------------------------------------------------------------- lifecycle

	public void registerChannels() {
		client.registerChannels();
	}

	public void close() {
		client.close();
	}

	// ------------------------------------------------------------------- api

	/** What the client is doing right now, for the status command. */
	public VaultClient.Status status() {
		return client.status();
	}

	/**
	 * The balance without waiting for anything.
	 *
	 * A placeholder resolving on the render thread cannot block on the proxy, so
	 * it reads what the last refresh left and takes zero when there is nothing.
	 */
	public VaultPlayerSnapshot cachedSnapshot(UUID playerId, String playerName) {
		return client.cachedSnapshot(playerId, playerName);
	}

	public VaultPlayerSnapshot cachedSnapshot(UUID playerId) {
		return client.cachedSnapshot(playerId);
	}

	@Override
	public CompletableFuture<VaultPlayerSnapshot> snapshot(UUID playerId, String playerName) {
		return client.snapshot(playerId, playerName);
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		return client.balance(playerId, playerName);
	}

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return client.deposit(actorId, actorName, playerId, playerName, amountMinor, source, details);
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return client.withdraw(actorId, actorName, playerId, playerName, amountMinor, source, details);
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		return client.transfer(senderId, senderName, receiverId, receiverName, amountMinor, source, details);
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		return client.setBalance(actorId, actorName, playerId, playerName, newBalanceMinor, source, details);
	}

	@Override
	public CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize) {
		return client.history(playerId, page, pageSize);
	}

	@Override
	public CompletableFuture<VaultLeaderboardPage> leaderboard(int page, int pageSize) {
		return client.leaderboard(page, pageSize);
	}
}
