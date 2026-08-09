package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Resolving an item by its registry name, as the 26.x line spells it.
 *
 * The 1.20-1.21 copy lives in luna-core-fabric/src/mc21; see it for why this file
 * exists twice. Two things moved here: {@code ResourceLocation} is now
 * {@code Identifier}, and the by-name lookup that returned the value directly is
 * now {@code getValue} - plain {@code get} answers with a registry holder.
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
		Identifier identifier;

		try {
			identifier = Identifier.fromNamespaceAndPath(namespace, path);
		} catch (RuntimeException malformed) {
			return null;
		}

		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			return null;
		}

		Item item = BuiltInRegistries.ITEM.getValue(identifier);

		return item == Items.AIR ? null : item;
	}
}
