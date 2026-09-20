package dev.belikhun.luna.vault.backend.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.client.VaultClient;
import dev.belikhun.luna.vault.api.client.VaultPlatform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * This backend's half of the network economy, on Paper.
 *
 * The proxy owns the money; this is the {@link VaultClient} bound to Bukkit's
 * player list and events. Everything that decides anything (the cache, the
 * request budget, the resend and settlement rules) lives in the shared client,
 * so Paper and the mod loaders cannot drift apart on how a balance is asked
 * for or a lost reply is chased.
 */
public final class PaperVaultGateway implements LunaVaultApi, Listener, VaultPlatform<Player> {
	private final VaultClient<Player> client;

	public PaperVaultGateway(JavaPlugin plugin, LunaLogger logger, PluginMessageBus<Player, Player> bus, String backendName, long requestTimeoutMillis) {
		this.client = new VaultClient<>(this, bus, logger.scope("Gateway"), backendName, requestTimeoutMillis);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	// -------------------------------------------------------------- platform

	@Override
	public UUID idOf(Player player) {
		return player.getUniqueId();
	}

	@Override
	public String nameOf(Player player) {
		return player.getName();
	}

	@Override
	public Player byId(UUID playerId) {
		Player online = Bukkit.getPlayer(playerId);

		if (online == null || !online.isOnline()) {
			return null;
		}

		return online;
	}

	@Override
	public Collection<? extends Player> online() {
		return Bukkit.getOnlinePlayers();
	}

	// ---------------------------------------------------------------- events

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerJoin(PlayerJoinEvent event) {
		client.onPlayerJoin(event.getPlayer());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		client.onPlayerQuit(event.getPlayer());
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
