package dev.belikhun.luna.core.mc.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * Writing a display name, lore and the enchantment shimmer onto a stack, for the
 * lines that still keep all three under the {@code display} NBT tag: 1.19
 * through 1.20.4.
 *
 * 1.20.5 replaced the tag with typed data components; those lines take
 * decor-components. Both write the name and lore as serialized chat json, which
 * is what the tag has always held.
 */
public final class ItemDecor {
	private static final String DISPLAY = "display";

	private ItemDecor() {
	}

	/** Set the stack's display name. */
	public static void name(ItemStack stack, Component name) {
		stack.getOrCreateTagElement(DISPLAY).putString("Name", Component.Serializer.toJson(name));
	}

	/** Replace the stack's lore with these lines, one component per line. */
	public static void lore(ItemStack stack, List<Component> lines) {
		ListTag serialized = new ListTag();

		for (Component line : lines) {
			serialized.add(StringTag.valueOf(Component.Serializer.toJson(line)));
		}

		stack.getOrCreateTagElement(DISPLAY).put("Lore", serialized);
	}

	/**
	 * Force the enchantment shimmer on.
	 *
	 * This line has no glint override: the shimmer is a property of being
	 * enchanted, so the only way to add one is to add an enchantment and hide it
	 * from the tooltip. Turning it *off* is therefore not possible here and the
	 * call is ignored - luna asks for that on plain menu buttons, which have no
	 * enchantment to suppress, so the button still renders as intended.
	 */
	public static void glint(ItemStack stack, boolean glint) {
		if (!glint) {
			return;
		}

		stack.enchant(Enchantments.UNBREAKING, 1);

		// bit 0 of HideFlags is the enchantment list; without it the button would
		// show "Unbreaking I" under its name
		CompoundTag tag = stack.getOrCreateTag();
		tag.putInt("HideFlags", tag.getInt("HideFlags") | 1);
	}

	/** The stack's display name, or null when it carries none of its own. */
	public static Component readName(ItemStack stack) {
		CompoundTag display = stack.getTagElement(DISPLAY);

		if (display == null || !display.contains("Name", Tag.TAG_STRING)) {
			return null;
		}

		return Component.Serializer.fromJson(display.getString("Name"));
	}

	/** The stack's lore lines, empty when it has none. */
	public static List<Component> readLore(ItemStack stack) {
		CompoundTag display = stack.getTagElement(DISPLAY);

		if (display == null || !display.contains("Lore", Tag.TAG_LIST)) {
			return List.of();
		}

		ListTag serialized = display.getList("Lore", Tag.TAG_STRING);
		List<Component> lines = new ArrayList<>();

		for (int index = 0; index < serialized.size(); index++) {
			Component line = Component.Serializer.fromJson(serialized.getString(index));
			lines.add(line == null ? Component.empty() : line);
		}

		return lines;
	}

	/**
	 * Whether two stacks are the same item carrying the same data.
	 *
	 * What "the same data" means is exactly what moved in 1.20.5: the nbt tag
	 * here, components on the newer lines. Ignores the count, as both do.
	 */
	public static boolean sameItemAndData(ItemStack first, ItemStack second) {
		return ItemStack.isSameItemSameTags(first, second);
	}
}
