package dev.belikhun.luna.shop.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * How a shop item and a category are named, wherever the shop runs.
 *
 * The id of an item is derived from the bytes the platform serialised it to, so
 * the same item added twice collides on its own rather than needing a registry.
 * The normalisation is what makes a category typed in chat, one written in
 * items.yml and one clicked in the GUI the same category.
 */
public final class ShopItemIds {
	private static final String FALLBACK_ITEM_ID = "item";
	private static final String FALLBACK_CATEGORY = "general";
	private static final String EMPTY_HASH = "0000000";
	private static final int HASH_LENGTH = 7;

	private ShopItemIds() {
	}

	public static String normalizeId(String value) {
		if (value == null || value.isBlank()) {
			return FALLBACK_ITEM_ID;
		}

		return value.trim().toLowerCase(Locale.ROOT).replace(" ", "-");
	}

	public static String normalizeCategory(String value) {
		if (value == null || value.isBlank()) {
			return FALLBACK_CATEGORY;
		}

		return value.trim().toLowerCase(Locale.ROOT).replace(" ", "-");
	}

	/**
	 * The last seven hex digits of the serialised item's SHA-256.
	 *
	 * @param serialized the platform's own bytes for a single-count copy of the item
	 */
	public static String hashId(byte[] serialized) {
		if (serialized == null || serialized.length == 0) {
			return EMPTY_HASH;
		}

		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(serialized);
			String hex = HexFormat.of().formatHex(hash);
			return normalizeId(hex.substring(Math.max(0, hex.length() - HASH_LENGTH)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Thuật toán SHA-256 không khả dụng", exception);
		}
	}
}
