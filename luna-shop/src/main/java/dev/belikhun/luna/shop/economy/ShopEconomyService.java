package dev.belikhun.luna.shop.economy;

import org.bukkit.entity.Player;

public interface ShopEconomyService {
	double balance(Player player);

	boolean has(Player player, double amount);

	boolean withdraw(Player player, double amount);

	boolean deposit(Player player, double amount);

	String format(double amount);
}