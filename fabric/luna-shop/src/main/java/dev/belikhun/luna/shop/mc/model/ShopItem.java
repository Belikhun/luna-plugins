package dev.belikhun.luna.shop.mc.model;

import dev.belikhun.luna.core.mc.ui.LunaItemCodec;
import dev.belikhun.luna.shop.api.ShopItemIds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * One thing the shop sells, and what it costs.
 *
 * The item itself is carried as an opaque string, because that is what survives
 * a restart in items.yml; {@link LunaItemCodec} is what turns it back into
 * something the game understands, and it needs the server for its registries.
 * That is the whole reason this class exists once per platform rather than in
 * luna-shop-api with the rest of the shop's model.
 *
 * A trade limit of zero means no limit, on both sides.
 */
public final class ShopItem {
	private final String id;
	private final String category;
	private final double buyPrice;
	private final double sellPrice;
	private final int buyTradeLimit;
	private final int sellTradeLimit;
	private final String itemData;
	private final long addedDate;

	public ShopItem(String id, String category, double buyPrice, double sellPrice, int buyTradeLimit, int sellTradeLimit, String itemData, long addedDate) {
		this.id = ShopItemIds.normalizeId(id);
		this.category = ShopItemIds.normalizeCategory(category);
		this.buyPrice = Math.max(0D, buyPrice);
		this.sellPrice = Math.max(0D, sellPrice);
		this.buyTradeLimit = Math.max(0, buyTradeLimit);
		this.sellTradeLimit = Math.max(0, sellTradeLimit);
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

	public int buyTradeLimit() {
		return buyTradeLimit;
	}

	public int sellTradeLimit() {
		return sellTradeLimit;
	}

	public boolean hasBuyTradeLimit() {
		return buyTradeLimit > 0;
	}

	public boolean hasSellTradeLimit() {
		return sellTradeLimit > 0;
	}

	public String itemData() {
		return itemData;
	}

	public long addedDate() {
		return addedDate;
	}

	/** A fresh copy of the item, count 1. {@link ItemStack#EMPTY} if unreadable. */
	public ItemStack itemStack(MinecraftServer server) {
		return LunaItemCodec.decode(server, itemData);
	}

	public ShopItem withId(String value) {
		return new ShopItem(value, category, buyPrice, sellPrice, buyTradeLimit, sellTradeLimit, itemData, addedDate);
	}

	public ShopItem withCategory(String value) {
		return new ShopItem(id, value, buyPrice, sellPrice, buyTradeLimit, sellTradeLimit, itemData, addedDate);
	}

	public ShopItem withBuyPrice(double value) {
		return new ShopItem(id, category, value, sellPrice, buyTradeLimit, sellTradeLimit, itemData, addedDate);
	}

	public ShopItem withSellPrice(double value) {
		return new ShopItem(id, category, buyPrice, value, buyTradeLimit, sellTradeLimit, itemData, addedDate);
	}

	public ShopItem withBuyTradeLimit(int value) {
		return new ShopItem(id, category, buyPrice, sellPrice, value, sellTradeLimit, itemData, addedDate);
	}

	public ShopItem withSellTradeLimit(int value) {
		return new ShopItem(id, category, buyPrice, sellPrice, buyTradeLimit, value, itemData, addedDate);
	}

	public ShopItem withItemData(String value) {
		return new ShopItem(id, category, buyPrice, sellPrice, buyTradeLimit, sellTradeLimit, value, addedDate);
	}

	public static ShopItem fromItemStack(
		MinecraftServer server,
		String id,
		String category,
		double buyPrice,
		double sellPrice,
		int buyTradeLimit,
		int sellTradeLimit,
		ItemStack itemStack,
		long addedDate
	) {
		return new ShopItem(
			id,
			category,
			buyPrice,
			sellPrice,
			buyTradeLimit,
			sellTradeLimit,
			LunaItemCodec.encode(server, itemStack),
			addedDate
		);
	}

	/** The same item added twice gets the same id, so a duplicate announces itself. */
	public static ShopItem fromItemStackAutoId(
		MinecraftServer server,
		String category,
		double buyPrice,
		double sellPrice,
		int buyTradeLimit,
		int sellTradeLimit,
		ItemStack itemStack
	) {
		String id = ShopItemIds.hashId(LunaItemCodec.fingerprint(server, itemStack));
		return fromItemStack(server, id, category, buyPrice, sellPrice, buyTradeLimit, sellTradeLimit, itemStack, System.currentTimeMillis());
	}
}
