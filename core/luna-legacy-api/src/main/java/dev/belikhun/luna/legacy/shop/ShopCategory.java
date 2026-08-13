package dev.belikhun.luna.legacy.shop;


import dev.belikhun.luna.legacy.string.Strings;
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
		return !Strings.isBlank(displayName);
	}

	/** The icon, falling back to a chest when the payload will not decode. */
	/**
	 * The icon, or the fallback when the stored one will not decode.
	 *
	 * The fallback is the codec's business rather than a hardcoded chest: an
	 * item id is a different thing on each game line, and the shop's model has
	 * no business naming one.
	 */
	public <I> I iconItem(ShopItems<I> codec, I fallback) {
		I decoded = codec.decode(iconData);

		return codec.isEmpty(decoded) ? fallback : decoded;
	}

	public ShopCategory withDisplayName(String value) {
		return new ShopCategory(id, iconData, value);
	}

	/**
	 * A category showing the given stack.
	 *
	 * An absent or unreadable icon stores nothing rather than a substitute, so the
	 * fallback is chosen once, where the category is drawn, by whoever knows what
	 * this game line calls a chest.
	 */
	public static <I> ShopCategory fromIcon(ShopItems<I> codec, String id, I icon) {
		String encoded = icon == null || codec.isEmpty(icon) ? "" : codec.encode(icon);

		return new ShopCategory(id, encoded, "");
	}

	public static ShopCategory defaultCategory(String id) {
		return new ShopCategory(id, "", "");
	}
}
