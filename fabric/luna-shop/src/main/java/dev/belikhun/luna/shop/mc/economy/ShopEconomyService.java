package dev.belikhun.luna.shop.mc.economy;

import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * What the shop needs from the wallet, and nothing more.
 *
 * The Paper shop has two implementations behind this - Vault and LunaVault -
 * because a Paper server might have either. Here there is only one: Vault is a
 * Bukkit service and does not exist on a mod loader, so LunaVault is the wallet.
 * The interface stays anyway, because it is what keeps the buy and sell logic
 * from knowing where the money lives.
 *
 * **Every operation comes in two forms, and the async one is the real one.** The
 * wallet lives on the proxy, so each of these is a network round trip, and the
 * shop is driven from a click on the server thread. The blocking forms hold that
 * thread for the whole trip; they remain for callers that have nowhere to hand a
 * continuation, and every new caller should take the async form.
 */
public interface ShopEconomyService {
	double balance(ServerPlayer player);

	boolean has(ServerPlayer player, double amount);

	boolean withdraw(ServerPlayer player, double amount);

	boolean deposit(ServerPlayer player, double amount);

	/** The player's balance, without waiting for it. */
	CompletableFuture<Double> balanceAsync(ServerPlayer player);

	/** Whether the player can afford this, without waiting for the answer. */
	CompletableFuture<Boolean> hasAsync(ServerPlayer player, double amount);

	/**
	 * Take money, and answer when the wallet has really said so.
	 *
	 * This cannot produce the half-done state its blocking twin can: nothing here
	 * ever stops waiting, so a transfer is never left in flight with the shop
	 * having already decided it failed.
	 */
	CompletableFuture<Boolean> withdrawAsync(ServerPlayer player, double amount);

	/** Give money, and answer when the wallet has really said so. */
	CompletableFuture<Boolean> depositAsync(ServerPlayer player, double amount);

	String format(double amount);
}
