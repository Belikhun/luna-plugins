package dev.belikhun.luna.shop.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

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

	@Override
	public String format(double amount) {
		return economy.format(amount);
	}
}