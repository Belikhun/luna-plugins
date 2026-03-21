package dev.belikhun.luna.vault.backend.service;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LunaVaultEconomyProvider implements Economy {
	private static final long MAIN_THREAD_AWAIT_MILLIS = 40L;

	private final JavaPlugin plugin;
	private final PaperVaultGateway gateway;
	private final long timeoutMillis;
	private final String currencySymbol;
	private final boolean moneyGrouping;
	private final String moneyTemplate;

	public LunaVaultEconomyProvider(JavaPlugin plugin, PaperVaultGateway gateway, ConfigStore coreConfig, long timeoutMillis) {
		this.plugin = plugin;
		this.gateway = gateway;
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		this.currencySymbol = coreConfig == null
			? VaultMoney.DEFAULT_CURRENCY_SYMBOL
			: coreConfig.get("strings.money.currencySymbol").asString(VaultMoney.DEFAULT_CURRENCY_SYMBOL);
		this.moneyGrouping = coreConfig == null || coreConfig.get("strings.money.grouping").asBoolean(true);
		this.moneyTemplate = coreConfig == null
			? VaultMoney.DEFAULT_TEMPLATE
			: coreConfig.get("strings.money.format").asString(VaultMoney.DEFAULT_TEMPLATE);
	}

	@Override
	public boolean isEnabled() {
		return plugin.isEnabled();
	}

	@Override
	public String getName() {
		return plugin.getName();
	}

	@Override
	public int fractionalDigits() {
		return 2;
	}

	@Override
	public String format(double amount) {
		long minor = VaultMoney.fromDouble(amount);
		return VaultMoney.format(minor, currencySymbol, moneyGrouping, moneyTemplate);
	}

	@Override
	public String currencyNamePlural() {
		return currencySymbol;
	}

	@Override
	public String currencyNameSingular() {
		return currencyNamePlural();
	}

	@Override
	public boolean hasAccount(OfflinePlayer player) {
		return player != null;
	}

	@Override
	public double getBalance(OfflinePlayer player) {
		if (player == null) {
			return 0D;
		}

		VaultPlayerSnapshot cached = gateway.cachedSnapshot(player.getUniqueId());
		if (cached != null) {
			return VaultMoney.toMajorDouble(cached.balanceMinor());
		}

		if (Bukkit.isPrimaryThread()) {
			gateway.snapshot(player.getUniqueId(), player.getName());
			return 0D;
		}

		VaultPlayerSnapshot snapshot = snapshot(player);
		return VaultMoney.toMajorDouble(snapshot == null ? 0L : snapshot.balanceMinor());
	}

	@Override
	public boolean has(OfflinePlayer player, double amount) {
		return getBalance(player) >= amount;
	}

	@Override
	public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
		if (player == null) {
			return failure(amount, 0D, "Người chơi không hợp lệ.");
		}

		VaultOperationResult result = await(gateway.withdraw(null, null, player.getUniqueId(), player.getName(), VaultMoney.fromDouble(amount), "vault", "Vault withdraw"), null);
		if (result == null || !result.success()) {
			return failure(amount, cachedBalance(player), result == null ? "Không thể trừ tiền." : result.message());
		}

		return success(amount, VaultMoney.toMajorDouble(result.balanceMinor()), result.message());
	}

	@Override
	public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
		if (player == null) {
			return failure(amount, 0D, "Người chơi không hợp lệ.");
		}

		VaultOperationResult result = await(gateway.deposit(null, null, player.getUniqueId(), player.getName(), VaultMoney.fromDouble(amount), "vault", "Vault deposit"), null);
		if (result == null || !result.success()) {
			return failure(amount, cachedBalance(player), result == null ? "Không thể cộng tiền." : result.message());
		}

		return success(amount, VaultMoney.toMajorDouble(result.balanceMinor()), result.message());
	}

	@Override
	public boolean createPlayerAccount(OfflinePlayer player) {
		return player != null && hasAccount(player);
	}

	@Override
	public boolean hasAccount(String playerName) {
		return hasAccount(offline(playerName));
	}

	@Override
	public boolean hasAccount(String playerName, String worldName) {
		return hasAccount(playerName);
	}

	@Override
	public boolean hasAccount(OfflinePlayer player, String worldName) {
		return hasAccount(player);
	}

	@Override
	public double getBalance(String playerName) {
		return getBalance(offline(playerName));
	}

	@Override
	public double getBalance(String playerName, String world) {
		return getBalance(playerName);
	}

	@Override
	public double getBalance(OfflinePlayer player, String world) {
		return getBalance(player);
	}

	@Override
	public boolean has(String playerName, double amount) {
		return has(offline(playerName), amount);
	}

	@Override
	public boolean has(String playerName, String worldName, double amount) {
		return has(playerName, amount);
	}

	@Override
	public boolean has(OfflinePlayer player, String worldName, double amount) {
		return has(player, amount);
	}

	@Override
	public EconomyResponse withdrawPlayer(String playerName, double amount) {
		return withdrawPlayer(offline(playerName), amount);
	}

	@Override
	public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
		return withdrawPlayer(playerName, amount);
	}

	@Override
	public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
		return withdrawPlayer(player, amount);
	}

	@Override
	public EconomyResponse depositPlayer(String playerName, double amount) {
		return depositPlayer(offline(playerName), amount);
	}

	@Override
	public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
		return depositPlayer(playerName, amount);
	}

	@Override
	public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
		return depositPlayer(player, amount);
	}

	@Override
	public boolean createPlayerAccount(String playerName) {
		return createPlayerAccount(offline(playerName));
	}

	@Override
	public boolean createPlayerAccount(String playerName, String worldName) {
		return createPlayerAccount(playerName);
	}

	@Override
	public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
		return createPlayerAccount(player);
	}

	@Override
	public boolean hasBankSupport() {
		return false;
	}

	@Override
	public EconomyResponse createBank(String name, String player) {
		return unsupported();
	}

	@Override
	public EconomyResponse createBank(String name, OfflinePlayer player) {
		return unsupported();
	}

	@Override
	public EconomyResponse deleteBank(String name) {
		return unsupported();
	}

	@Override
	public EconomyResponse bankBalance(String name) {
		return unsupported();
	}

	@Override
	public EconomyResponse bankHas(String name, double amount) {
		return unsupported();
	}

	@Override
	public EconomyResponse bankWithdraw(String name, double amount) {
		return unsupported();
	}

	@Override
	public EconomyResponse bankDeposit(String name, double amount) {
		return unsupported();
	}

	@Override
	public EconomyResponse isBankOwner(String name, String playerName) {
		return unsupported();
	}

	@Override
	public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
		return unsupported();
	}

	@Override
	public EconomyResponse isBankMember(String name, String playerName) {
		return unsupported();
	}

	@Override
	public EconomyResponse isBankMember(String name, OfflinePlayer player) {
		return unsupported();
	}

	@Override
	public List<String> getBanks() {
		return Collections.emptyList();
	}

	private OfflinePlayer offline(String playerName) {
		Player online = Bukkit.getPlayerExact(playerName);
		return online != null ? online : Bukkit.getOfflinePlayer(playerName);
	}

	private EconomyResponse success(double amount, double balance, String message) {
		return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, message == null ? "Successful" : message);
	}

	private EconomyResponse failure(double amount, double balance, String message) {
		return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message == null ? "Failed" : message);
	}

	private EconomyResponse unsupported() {
		return new EconomyResponse(0D, 0D, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank is unsupported.");
	}

	private <T> T await(CompletableFuture<T> future, T fallback) {
		if (future == null) {
			return fallback;
		}

		try {
			if (Bukkit.isPrimaryThread()) {
				return future.isDone() ? future.getNow(fallback) : fallback;
			}

			return future.get(timeoutMillis + 250L, TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			return fallback;
		}
	}

	private VaultPlayerSnapshot snapshot(OfflinePlayer player) {
		if (player == null) {
			return null;
		}

		return await(gateway.snapshot(player.getUniqueId(), player.getName()), null);
	}

	private double cachedBalance(OfflinePlayer player) {
		if (player == null) {
			return 0D;
		}

		VaultPlayerSnapshot cached = gateway.cachedSnapshot(player.getUniqueId());
		if (cached == null) {
			return 0D;
		}

		return VaultMoney.toMajorDouble(cached.balanceMinor());
	}
}
