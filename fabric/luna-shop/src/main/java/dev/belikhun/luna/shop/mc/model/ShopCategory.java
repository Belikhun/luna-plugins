package dev.belikhun.luna.shop.mc.model;

import dev.belikhun.luna.core.mc.ui.LunaItemCodec;
import dev.belikhun.luna.shop.api.ShopItemIds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/**
 * A shelf in the shop: an id, the item drawn as its icon, and an optional label.
 *
 * Without a display name the GUI prettifies the id ("redstone-gear" reads as
 * "Redstone Gear"), which is why a blank one is normal rather than missing.
 */
public final class ShopCategory {
	private final String id;
	private final String iconData;
	private final String displayName;

	public ShopCategory(String id, String iconData, String displayName) {
		this.id = ShopItemIds.normalizeCategory(id);
		this.iconData = Objects.requireNonNull(iconData, "iconData");
		this.displayName = displayName == null ? "" : displayName.trim();
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

	/** The icon, falling back to a chest when the payload will not decode. */
	public ItemStack iconItem(MinecraftServer server) {
		ItemStack decoded = LunaItemCodec.decode(server, iconData);
		return decoded.isEmpty() ? new ItemStack(Items.CHEST) : decoded;
	}

	public ShopCategory withDisplayName(String value) {
		return new ShopCategory(id, iconData, value);
	}

	public static ShopCategory fromIcon(MinecraftServer server, String id, ItemStack icon) {
		ItemStack source = icon == null || icon.isEmpty() ? new ItemStack(Items.CHEST) : icon;
		return new ShopCategory(id, LunaItemCodec.encode(server, source), "");
	}

	public static ShopCategory defaultCategory(MinecraftServer server, String id) {
		return fromIcon(server, id, new ItemStack(Items.CHEST));
	}
}
