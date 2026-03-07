package dev.belikhun.luna.smp.repair;

import dev.belikhun.luna.core.LunaCore;
import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.string.Formatters;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

public final class ToolRepairService {
	private final ConfigStore configStore;
	private final Economy economy;

	public ToolRepairService(ConfigStore configStore, Economy economy) {
		this.configStore = configStore;
		this.economy = economy;
	}

	public boolean isRepairable(ItemStack item) {
		if (item == null) {
			return false;
		}

		Material material = item.getType();
		if (material == Material.AIR) {
			return false;
		}

		if (material.getMaxDurability() <= 0) {
			return false;
		}

		ItemMeta meta = item.getItemMeta();
		return meta instanceof Damageable;
	}

	public boolean isDamaged(ItemStack item) {
		if (!isRepairable(item)) {
			return false;
		}

		Damageable damageable = (Damageable) item.getItemMeta();
		return damageable.getDamage() > 0;
	}

	public double calculateRepairCost(ItemStack item) {
		return quote(item).finalCost();
	}

	public RepairQuote quote(ItemStack item) {
		Damageable damageable = (Damageable) item.getItemMeta();
		double maxDamage = item.getType().getMaxDurability();
		double damageRatio = damageable.getDamage() / maxDamage;
		double remainingPercent = Math.max(0.0D, (1.0D - damageRatio) * 100.0D);

		double baseCost = configStore.get("tool-repair.base-cost").asDouble(50.0D);
		double costPercentage = configStore.get("tool-repair.cost-percentage").asDouble(500.0D);
		double damageCost = costPercentage * damageRatio;
		double finalCost = baseCost + damageCost;

		return new RepairQuote(
			round(remainingPercent),
			round(baseCost),
			round(damageCost),
			round(finalCost)
		);
	}

	public String message(String path) {
		return configStore.get(path).asString("<red>Thiếu message: " + path + "</red>");
	}

	public String formatMoney(double amount) {
		ConfigStore coreConfig = LunaCore.services().configStore();
		String moneySymbol = coreConfig.get("strings.money.currencySymbol").asString("₫");
		boolean moneyGrouping = coreConfig.get("strings.money.grouping").asBoolean(true);
		String moneyFormat = coreConfig.get("strings.money.format").asString("{amount}{symbol}");
		return Formatters.money(amount, moneySymbol, moneyGrouping, moneyFormat);
	}

	public boolean hasEnoughMoney(Player player, double amount) {
		return economy != null && economy.getBalance(player) >= amount;
	}

	public boolean withdraw(Player player, double amount) {
		if (!hasEnoughMoney(player, amount)) {
			return false;
		}

		economy.withdrawPlayer(player, amount);
		return true;
	}

	public void repair(ItemStack item) {
		Damageable damageable = (Damageable) item.getItemMeta();
		damageable.setDamage(0);
		item.setItemMeta((ItemMeta) damageable);
	}

	private double round(double value) {
		return Math.round(value * 100.0D) / 100.0D;
	}

	public record RepairQuote(double remainingDurabilityPercent, double baseCost, double damageCost, double finalCost) {
	}
}
