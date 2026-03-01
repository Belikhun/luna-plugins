package dev.belikhun.luna.shop.model;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class ShopItem {
	private final String id;
	private final String category;
	private final double buyPrice;
	private final double sellPrice;
	private final String itemData;
	private final long addedDate;

	public ShopItem(String id, String category, double buyPrice, double sellPrice, String itemData) {
		this(id, category, buyPrice, sellPrice, itemData, System.currentTimeMillis());
	}

	public ShopItem(String id, String category, double buyPrice, double sellPrice, String itemData, long addedDate) {
		this.id = normalizeId(id);
		this.category = normalizeCategory(category);
		this.buyPrice = Math.max(0D, buyPrice);
		this.sellPrice = Math.max(0D, sellPrice);
		this.itemData = Objects.requireNonNull(itemData, "itemData");
		this.addedDate = Math.max(0L, addedDate);
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

	public long addedDate() {
		return addedDate;
	}

	public ItemStack itemStack() {
		byte[] bytes = Base64.getDecoder().decode(itemData);
		return ItemStack.deserializeBytes(bytes);
	}

	public static ShopItem fromItemStack(String id, String category, double buyPrice, double sellPrice, ItemStack itemStack) {
		return fromItemStack(id, category, buyPrice, sellPrice, itemStack, System.currentTimeMillis());
	}

	public static ShopItem fromItemStack(String id, String category, double buyPrice, double sellPrice, ItemStack itemStack, long addedDate) {
		ItemStack cloned = itemStack.clone();
		cloned.setAmount(1);
		String encoded = Base64.getEncoder().encodeToString(cloned.serializeAsBytes());
		return new ShopItem(id, category, buyPrice, sellPrice, encoded, addedDate);
	}

	public static ShopItem fromItemStackAutoId(String category, double buyPrice, double sellPrice, ItemStack itemStack) {
		return fromItemStackAutoId(category, buyPrice, sellPrice, itemStack, System.currentTimeMillis());
	}

	public static ShopItem fromItemStackAutoId(String category, double buyPrice, double sellPrice, ItemStack itemStack, long addedDate) {
		String id = hashId(itemStack);
		return fromItemStack(id, category, buyPrice, sellPrice, itemStack, addedDate);
	}

	public static String hashId(ItemStack itemStack) {
		if (itemStack == null) {
			return "0000000";
		}

		ItemStack normalized = itemStack.clone();
		normalized.setAmount(1);
		byte[] serialized = normalized.serializeAsBytes();
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(serialized);
			String hex = HexFormat.of().formatHex(hash);
			return normalizeId(hex.substring(Math.max(0, hex.length() - 7)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Thuật toán SHA-256 không khả dụng", exception);
		}
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