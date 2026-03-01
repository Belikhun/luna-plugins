package dev.belikhun.luna.shop.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Objects;

public final class ShopCategory {
	private final String id;
	private final String iconData;
	private final String displayName;

	public ShopCategory(String id, String iconData, String displayName) {
		this.id = ShopItem.normalizeCategory(id);
		this.iconData = Objects.requireNonNull(iconData, "iconData");
		String normalizedDisplayName = displayName == null ? "" : displayName.trim();
		this.displayName = normalizedDisplayName;
	}

	public String id() {
		return id;
	}

	public String iconData() {
		return iconData;
	}

	public String displayName() {
		return displayName;
	}

	public boolean hasDisplayName() {
		return !displayName.isBlank();
	}

	public ItemStack iconItem() {
		byte[] bytes = Base64.getDecoder().decode(iconData);
		return ItemStack.deserializeBytes(bytes);
	}

	public static ShopCategory fromIcon(String id, ItemStack icon) {
		ItemStack cloned = icon == null ? new ItemStack(Material.CHEST) : icon.clone();
		cloned.setAmount(1);
		String encoded = Base64.getEncoder().encodeToString(cloned.serializeAsBytes());
		return new ShopCategory(id, encoded, "");
	}

	public ShopCategory withDisplayName(String value) {
		return new ShopCategory(id, iconData, value);
	}

	public static ShopCategory defaultCategory(String id) {
		return fromIcon(id, new ItemStack(Material.CHEST));
	}
}