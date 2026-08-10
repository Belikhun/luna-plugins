package dev.belikhun.luna.core.mc.ui;

import dev.belikhun.luna.core.mc.compat.ItemDecor;
import dev.belikhun.luna.core.mc.compat.ItemLookup;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The buttons a luna screen is made of.
 *
 * Every screen wants the same thing: an item named by string, a MiniMessage
 * title, some MiniMessage lore, sometimes a glint or a stack size. Paper gets
 * this from {@code LunaUi.item}, which cannot serve here - it returns Bukkit's
 * ItemStack - so this is the same idea against the game's own types.
 *
 * An unknown item name renders as a barrier rather than throwing: a typo in a
 * shop's items.yml should show up as one wrong button, not a screen that will
 * not open.
 */
public final class LunaItems {
	private LunaItems() {
	}

	public static ItemStack of(String materialName, String title, List<String> loreLines) {
		return of(materialName, title, loreLines, null, 1);
	}

	public static ItemStack of(String materialName, String title, List<String> loreLines, Boolean glintOverride) {
		return of(materialName, title, loreLines, glintOverride, 1);
	}

	/**
	 * @param materialName an item id, with or without its namespace
	 * @param title MiniMessage; the client's italic default is already undone
	 * @param loreLines MiniMessage, one entry per line
	 * @param glintOverride force the enchantment shimmer on or off, or null to leave it
	 * @param count the stack size shown in the corner, clamped to 1-64
	 */
	public static ItemStack of(String materialName, String title, List<String> loreLines, Boolean glintOverride, int count) {
		Item item = resolve(materialName);
		ItemStack stack = new ItemStack(item == null ? Items.BARRIER : item, Math.max(1, Math.min(64, count)));

		ItemDecor.name(stack, LunaTextComponents.mini(safe(title)));

		if (loreLines != null && !loreLines.isEmpty()) {
			List<Component> lore = new ArrayList<>();

			for (String line : loreLines) {
				lore.add(LunaTextComponents.mini(safe(line)));
			}

			ItemDecor.lore(stack, lore);
		}

		if (glintOverride != null) {
			ItemDecor.glint(stack, glintOverride);
		}

		return stack;
	}

	/** Resolve an item id, namespaced or not. Null when the game has no such item. */
	public static Item resolve(String materialName) {
		if (materialName == null || materialName.isBlank()) {
			return null;
		}

		String normalized = materialName.trim().toLowerCase(Locale.ROOT);
		String namespace = "minecraft";
		String path = normalized;
		int separator = normalized.indexOf(':');

		if (separator > 0 && separator < normalized.length() - 1) {
			namespace = normalized.substring(0, separator);
			path = normalized.substring(separator + 1);
		}

		return ItemLookup.byName(namespace, path);
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}
}
