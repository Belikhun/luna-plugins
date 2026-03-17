package dev.belikhun.luna.shop.economy;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LunaVaultEconomyService implements ShopEconomyService {
	private static final String ACTOR_NAME = "LunaShop";
	private static final String SOURCE = "lunashop";

	private final LunaVaultApi vaultApi;
	private final long timeoutMillis;
	private final ConfigStore coreConfig;

	private LunaVaultEconomyService(LunaVaultApi vaultApi, long timeoutMillis, ConfigStore coreConfig) {
		this.vaultApi = vaultApi;
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		this.coreConfig = coreConfig;
	}

	public static Optional<ShopEconomyService> create(JavaPlugin plugin) {
		RegisteredServiceProvider<LunaVaultApi> provider = plugin.getServer().getServicesManager().getRegistration(LunaVaultApi.class);
		if (provider == null || provider.getProvider() == null) {
			return Optional.empty();
		}

		long timeoutMillis = 3000L;
		org.bukkit.plugin.Plugin backendPlugin = plugin.getServer().getPluginManager().getPlugin("LunaVaultBackend");
		if (backendPlugin instanceof JavaPlugin javaPlugin) {
			timeoutMillis = javaPlugin.getConfig().getLong("transport.timeout-millis", 3000L);
		}

		return Optional.of(new LunaVaultEconomyService(provider.getProvider(), timeoutMillis, LunaCore.services().configStore()));
	}

	@Override
	public double balance(Player player) {
		if (player == null) {
			return 0D;
		}

		long balanceMinor = await(vaultApi.balance(player.getUniqueId(), player.getName()), 0L);
		return VaultMoney.toMajorDouble(balanceMinor);
	}

	@Override
	public boolean has(Player player, double amount) {
		if (player == null || amount < 0D) {
			return false;
		}

		return await(vaultApi.has(player.getUniqueId(), player.getName(), VaultMoney.fromDouble(amount)), false);
	}

	@Override
	public boolean withdraw(Player player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		VaultOperationResult result = await(vaultApi.withdraw(null, ACTOR_NAME, player.getUniqueId(), player.getName(), VaultMoney.fromDouble(amount), SOURCE, "Mua vật phẩm từ LunaShop"), null);
		return result != null && result.success();
	}

	@Override
	public boolean deposit(Player player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		VaultOperationResult result = await(vaultApi.deposit(null, ACTOR_NAME, player.getUniqueId(), player.getName(), VaultMoney.fromDouble(amount), SOURCE, "Bán vật phẩm cho LunaShop"), null);
		return result != null && result.success();
	}

	@Override
	public String format(double amount) {
		return Formatters.money(coreConfig, amount);
	}

	private <T> T await(CompletableFuture<T> future, T fallback) {
		try {
			return future.get(timeoutMillis + 250L, TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			return fallback;
		}
	}
}
