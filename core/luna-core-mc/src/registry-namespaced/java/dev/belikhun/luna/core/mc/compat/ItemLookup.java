package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Resolving an item by its registry name, for the lines carrying the factory
 * method: 1.20.5 through 1.21.x.
 *
 * Older lines still have the public constructor and get registry-ctor; 26.x
 * renamed {@code ResourceLocation} to {@code Identifier} and moved the by-name
 * lookup from {@code get} to {@code getValue}, and gets registry-identifier. See
 * this module's README for how a platform composes its set.
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
			identifier = ResourceLocation.fromNamespaceAndPath(namespace, path);
		} catch (RuntimeException malformed) {
			return null;
		}

		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			return null;
		}

		Item item = BuiltInRegistries.ITEM.get(identifier);

		return item == Items.AIR ? null : item;
	}
}
