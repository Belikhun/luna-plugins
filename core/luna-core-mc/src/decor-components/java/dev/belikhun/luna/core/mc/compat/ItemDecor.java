package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * Writing a display name, lore and the enchantment shimmer onto a stack, for the
 * lines that carry data components: 1.20.5 through 26.x.
 *
 * 1.20.5 replaced the {@code display} NBT tag with typed components, so every
 * older line takes decor-nbt instead. Only these three writes moved, which is
 * why {@link dev.belikhun.luna.core.mc.ui.LunaItems} keeps the rest - the
 * MiniMessage rendering, the barrier fallback, the count clamp - in the trunk.
 */
public final class ItemDecor {
	private ItemDecor() {
	}

	/** Set the stack's display name. */
	public static void name(ItemStack stack, Component name) {
		stack.set(DataComponents.CUSTOM_NAME, name);
	}

	/** Replace the stack's lore with these lines, one component per line. */
	public static void lore(ItemStack stack, List<Component> lines) {
		stack.set(DataComponents.LORE, new ItemLore(lines));
	}

	/** Force the enchantment shimmer on or off, whatever the stack carries. */
	public static void glint(ItemStack stack, boolean glint) {
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
	}

	/** The stack's display name, or null when it carries none of its own. */
	public static Component readName(ItemStack stack) {
		return stack.get(DataComponents.CUSTOM_NAME);
	}

	/** The stack's lore lines, empty when it has none. */
	public static List<Component> readLore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);

		return lore == null ? List.of() : lore.lines();
	}

	/**
	 * Whether two stacks are the same item carrying the same data.
	 *
	 * What "the same data" means is exactly what moved in 1.20.5: components here,
	 * the nbt tag on the older lines. Ignores the count, as both spellings do.
	 */
	public static boolean sameItemAndData(ItemStack first, ItemStack second) {
		return ItemStack.isSameItemSameComponents(first, second);
	}
}
