package dev.belikhun.luna.shop.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class VaultEconomyService implements ShopEconomyService {
	private final Economy economy;

	private VaultEconomyService(Economy economy) {
		this.economy = economy;
	}

	public static Optional<ShopEconomyService> create(JavaPlugin plugin) {
		RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
		if (provider == null || provider.getProvider() == null) {
			return Optional.empty();
		}

		return Optional.of(new VaultEconomyService(provider.getProvider()));
	}

	@Override
	public double balance(Player player) {
		return economy.getBalance(player);
	}

	@Override
	public boolean has(Player player, double amount) {
		return economy.has(player, amount);
	}

	@Override
	public boolean withdraw(Player player, double amount) {
		EconomyResponse response = economy.withdrawPlayer(player, amount);
		return response.transactionSuccess();
	}

	@Override
	public boolean deposit(Player player, double amount) {
		EconomyResponse response = economy.depositPlayer(player, amount);
		return response.transactionSuccess();
	}

	/**
	 * Vault has no async form, and must not be given one.
	 *
	 * These complete inline, on whatever thread called them, which for a trade is
	 * the main thread. That is deliberate: Vault implementations are Bukkit
	 * services and are not thread-safe, so handing one to another thread to look
	 * asynchronous would trade a stall this call does not have for a data race it
	 * currently cannot have. There is no network here to wait on.
	 */
	@Override
	public CompletableFuture<Double> balanceAsync(Player player) {
		return CompletableFuture.completedFuture(Double.valueOf(balance(player)));
	}

	@Override
	public CompletableFuture<Boolean> hasAsync(Player player, double amount) {
		return CompletableFuture.completedFuture(Boolean.valueOf(has(player, amount)));
	}

	@Override
	public CompletableFuture<Boolean> withdrawAsync(Player player, double amount) {
		return CompletableFuture.completedFuture(Boolean.valueOf(withdraw(player, amount)));
	}

	@Override
	public CompletableFuture<Boolean> depositAsync(Player player, double amount) {
		return CompletableFuture.completedFuture(Boolean.valueOf(deposit(player, amount)));
	}

	@Override
	public String format(double amount) {
		return economy.format(amount);
	}
}
