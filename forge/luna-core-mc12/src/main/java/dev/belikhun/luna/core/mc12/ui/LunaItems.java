package dev.belikhun.luna.core.mc12.ui;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Locale;

/**
 * Building the item a button is drawn as, on 1.12.2.
 *
 * An unknown item name renders as a barrier rather than throwing: a typo in a
 * shop's items.yml should show up as one wrong button, not a screen that will not
 * open.
 *
 * **Item names carry a damage value on this line**, and callers do not have to
 * know that. The flattening was 1.13, so `black_stained_glass_pane` is really
 * `stained_glass_pane` with damage 15 here; {@link LegacyItemNames} translates the
 * modern vocabulary, so a screen and a cluster config can name items the same way
 * on every backend. An explicit `:<damage>` suffix is still honoured.
 *
 * Names and lore are written as NBT strings with section-sign formatting rather
 * than as components: 1.12.2 renders `display.Name` as a plain string, and a JSON
 * component there shows up literally.
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
	 * @param materialName an item id, with or without its namespace, optionally
	 *                     followed by `:<damage>`
	 * @param title MiniMessage
	 * @param loreLines MiniMessage, one entry per line
	 * @param glintOverride force the enchantment shimmer on or off, or null to leave it
	 * @param count the stack size shown in the corner, clamped to 1-64
	 */
	public static ItemStack of(String materialName, String title, List<String> loreLines, Boolean glintOverride, int count) {
		Item item = resolve(materialName);
		ItemStack stack = new ItemStack(
			item == null ? barrier() : item,
			Math.max(1, Math.min(64, count)),
			item == null ? 0 : resolveMeta(materialName)
		);

		stack.setStackDisplayName(rendered(title));

		if (loreLines != null && !loreLines.isEmpty()) {
			NBTTagList lore = new NBTTagList();

			for (String line : loreLines) {
				lore.appendTag(new NBTTagString(rendered(line)));
			}

			display(stack).setTag("Lore", lore);
		}

		if (glintOverride != null) {
			glint(stack, glintOverride.booleanValue());
		}

		return stack;
	}

	/**
	 * Name and lore onto a stack that already exists.
	 *
	 * The shop draws a button *as* the item it sells, so the stack comes from the
	 * store's own payload rather than from a name; this puts luna's label on it
	 * without rebuilding it and losing whatever NBT made it that item.
	 *
	 * A null title leaves the item's own name alone, which is what a shop wants
	 * for anything a player named themselves.
	 *
	 * @param title MiniMessage, or null to keep the stack's existing name
	 * @param loreLines MiniMessage, one entry per line; null leaves lore alone
	 * @param count the stack size shown in the corner, clamped to 1-64
	 */
	public static ItemStack decorate(ItemStack stack, String title, List<String> loreLines, int count) {
		if (stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack copy = stack.copy();

		copy.setCount(Math.max(1, Math.min(64, count)));

		if (!Strings.isBlank(title)) {
			copy.setStackDisplayName(rendered(title));
		}

		if (loreLines != null && !loreLines.isEmpty()) {
			NBTTagList lore = new NBTTagList();

			for (String line : loreLines) {
				lore.appendTag(new NBTTagString(rendered(line)));
			}

			display(copy).setTag("Lore", lore);
		}

		return copy;
	}

	/**
	 * What an unresolvable name renders as.
	 *
	 * Barrier is a block on this line and only became an item in its own right
	 * later, so it is reached through its block form rather than through `Items`.
	 */
	private static Item barrier() {
		return Item.getItemFromBlock(Blocks.BARRIER);
	}

	/** Resolve an item id, namespaced or not, with any damage suffix removed. */
	public static Item resolve(String materialName) {
		if (Strings.isBlank(materialName)) {
			return null;
		}

		String[] parts = split(materialName);

		return Item.REGISTRY.getObject(new ResourceLocation(parts[0], parts[1]));
	}

	/** The damage value a name asks for, or 0. */
	public static int resolveMeta(String materialName) {
		if (Strings.isBlank(materialName)) {
			return 0;
		}

		try {
			return Integer.parseInt(split(materialName)[2]);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	/**
	 * Split a name into namespace, path and damage.
	 *
	 * The ambiguity is real: `a:b` is either a namespaced id or a path with a
	 * damage value. A trailing all-digits segment is read as damage, which is what
	 * makes `stained_glass_pane:15` work without forcing every config to spell out
	 * `minecraft:`.
	 */
	private static String[] split(String materialName) {
		// modern names first, so a screen and a cluster config can both speak the
		// post-flattening vocabulary and get the right damage value here
		String normalized = LegacyItemNames.translate(materialName.trim().toLowerCase(Locale.ROOT));
		String namespace = "minecraft";
		String meta = "0";
		String path = normalized;

		int last = path.lastIndexOf(':');

		if (last > 0 && isNumber(path.substring(last + 1))) {
			meta = path.substring(last + 1);
			path = path.substring(0, last);
		}

		int separator = path.indexOf(':');

		if (separator > 0 && separator < path.length() - 1) {
			namespace = path.substring(0, separator);
			path = path.substring(separator + 1);
		}

		return new String[] { namespace, path, meta };
	}

	private static boolean isNumber(String value) {
		if (value.isEmpty()) {
			return false;
		}

		for (int index = 0; index < value.length(); index += 1) {
			if (!Character.isDigit(value.charAt(index))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The shimmer, without an enchantment.
	 *
	 * An empty `ench` list is enough for the client to draw the glint, and unlike a
	 * real enchantment it changes nothing about the item.
	 */
	private static void glint(ItemStack stack, boolean enabled) {
		if (!enabled) {
			if (stack.hasTagCompound()) {
				stack.getTagCompound().removeTag("ench");
			}

			return;
		}

		stack.setTagInfo("ench", new NBTTagList());
	}

	private static NBTTagCompound display(ItemStack stack) {
		if (!stack.hasTagCompound()) {
			stack.setTagCompound(new NBTTagCompound());
		}

		NBTTagCompound tag = stack.getTagCompound();

		if (!tag.hasKey("display")) {
			tag.setTag("display", new NBTTagCompound());
		}

		return tag.getCompoundTag("display");
	}

	/** MiniMessage rendered to the section-sign string this version draws. */
	private static String rendered(String miniMessage) {
		return LunaTextComponents.mini(miniMessage == null ? "" : miniMessage).getFormattedText();
	}
}
