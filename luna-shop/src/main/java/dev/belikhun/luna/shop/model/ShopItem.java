package dev.belikhun.luna.shop.model;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Objects;

public final class ShopItem {
	private final String id;
	private final String category;
	private final double buyPrice;
	private final double sellPrice;
	private final String itemData;

	public ShopItem(String id, String category, double buyPrice, double sellPrice, String itemData) {
		this.id = normalizeId(id);
		this.category = normalizeCategory(category);
		this.buyPrice = Math.max(0D, buyPrice);
		this.sellPrice = Math.max(0D, sellPrice);
		this.itemData = Objects.requireNonNull(itemData, "itemData");
	}

	public String id() {
		return id;
	}

	public String category() {
		return category;
	}

	public double buyPrice() {
		return buyPrice;
	}

	public double sellPrice() {
		return sellPrice;
	}

	public String itemData() {
		return itemData;
	}

	public ItemStack itemStack() {
		byte[] bytes = Base64.getDecoder().decode(itemData);
		return ItemStack.deserializeBytes(bytes);
	}

	public static ShopItem fromItemStack(String id, String category, double buyPrice, double sellPrice, ItemStack itemStack) {
		ItemStack cloned = itemStack.clone();
		cloned.setAmount(1);
		String encoded = Base64.getEncoder().encodeToString(cloned.serializeAsBytes());
		return new ShopItem(id, category, buyPrice, sellPrice, encoded);
	}

	public static String normalizeId(String value) {
		if (value == null || value.isBlank()) {
			return "item";
		}

		return value.trim().toLowerCase().replace(" ", "-");
	}

	public static String normalizeCategory(String value) {
		if (value == null || value.isBlank()) {
			return "general";
		}

		return value.trim().toLowerCase().replace(" ", "-");
	}
}