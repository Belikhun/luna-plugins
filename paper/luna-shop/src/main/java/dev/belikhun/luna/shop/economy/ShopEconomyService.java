package dev.belikhun.luna.shop.economy;

import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * What the shop needs from the wallet, and nothing more.
 *
 * Two implementations sit behind this, and they differ in the one way that
 * matters here: Vault is a local Bukkit service and LunaVault is a network call
 * to the proxy. The buy and sell rules must not know which they have.
 *
 * **Every operation comes in two forms, and the async one is the real one.** A
 * trade is driven from a click on the main thread, so waiting there for a wallet
 * that lives on the proxy stops the tick for everyone to serve one player. The
 * blocking forms remain for callers that have nowhere to hand a continuation.
 *
 * An implementation with no network - Vault - answers its async form inline and
 * on the calling thread, which is not a shortcut but a requirement: Vault is not
 * safe to call off the main thread, and a wrapper that moved it there would be a
 * worse bug than the stall it was meant to avoid.
 */
public interface ShopEconomyService {
	double balance(Player player);

	boolean has(Player player, double amount);

	boolean withdraw(Player player, double amount);

	boolean deposit(Player player, double amount);

	/** The player's balance, without waiting for it. */
	CompletableFuture<Double> balanceAsync(Player player);

	/** Whether the player can afford this, without waiting for the answer. */
	CompletableFuture<Boolean> hasAsync(Player player, double amount);

	/**
	 * Take money, and answer when the wallet has really said so.
	 *
	 * This cannot produce the half-done state its blocking twin can: nothing here
	 * ever stops waiting, so a transfer is never left in flight with the shop
	 * having already decided it failed.
	 */
	CompletableFuture<Boolean> withdrawAsync(Player player, double amount);

	/** Give money, and answer when the wallet has really said so. */
	CompletableFuture<Boolean> depositAsync(Player player, double amount);

	String format(double amount);
}
