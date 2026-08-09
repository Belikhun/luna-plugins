package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Resolving an item by its registry name, as the 1.20-1.21 line spells it.
 *
 * 26.x renamed {@code ResourceLocation} to {@code Identifier} and moved the
 * by-name lookup from {@code get} to {@code getValue}, so this is the second file
 * that exists once per game line; the 26.x copy lives in luna-core-mc26-fabric
 * under the same name.
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
