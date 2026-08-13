package dev.belikhun.luna.legacy.shop;


/**
 * What the shop needs from the wallet, and nothing more.
 *
 * The Paper shop has two implementations behind this - Vault and LunaVault -
 * because a Paper server might have either. Here there is only one: Vault is a
 * Bukkit service and does not exist on a mod loader, so LunaVault is the wallet.
 * The interface stays anyway, because it is what keeps the buy and sell logic
 * from knowing where the money lives.
 */
public interface ShopEconomyService<P> {
	double balance(P player);

	boolean has(P player, double amount);

	boolean withdraw(P player, double amount);

	boolean deposit(P player, double amount);

	String format(double amount);
}
