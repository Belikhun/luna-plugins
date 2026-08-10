package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Resolving an item by its registry name, for the lines that still build one
 * through the public constructor: 1.19 through 1.20.4.
 *
 * {@code fromNamespaceAndPath} arrived when the constructor was made private, so
 * newer lines take registry-namespaced and 26.x takes registry-identifier. The
 * body is otherwise the same on all three. See this module's README.
 */
public final class ItemLookup {
	private ItemLookup() {
	}

	/**
	 * The item registered under {@code namespace:path}, or null when there is
	 * none.
	 *
	 * A name the registry would reject outright is treated as a name it does not
	 * have, because both mean the same thing to the caller: the operator wrote a
	 * material this server cannot draw, and the slot falls back to a barrier.
	 */
	public static Item byName(String namespace, String path) {
		ResourceLocation identifier;

		try {
			identifier = new ResourceLocation(namespace, path);
		} catch (RuntimeException malformed) {
			return null;
		}

		if (!BuiltInRegistries.ITEM.containsKey(identifier)) {
			return null;
		}

		Item item = BuiltInRegistries.ITEM.get(identifier);

		return item == Items.AIR ? null : item;
	}
}
