package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Resolving an item by its registry name, as the 26.x line spells it: the class
 * is {@code Identifier}, and {@code get} returns a holder so the value lookup is
 * {@code getValue}.
 *
 * Every older line keeps {@code ResourceLocation} and a {@code get} that answers
 * with the value itself, and takes registry-namespaced or registry-ctor
 * depending on how it spells the name. See this module's README.
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
