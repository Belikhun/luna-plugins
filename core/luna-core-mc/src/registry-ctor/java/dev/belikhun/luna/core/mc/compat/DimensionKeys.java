package dev.belikhun.luna.core.mc.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Turning a world id back into a dimension key, for the lines that still build an identifier through the public
 * constructor: 1.19 through 1.20.4.
 *
 * The registry key type itself is stable; only the identifier class it is built
 * from, and the accessor that reads it back, moved. This is the same one-method
 * split {@link ItemLookup} is, and it lives in the same compat sets. See this
 * module's README.
 */
public final class DimensionKeys {
	private DimensionKeys() {
	}

	/** The dimension named by {@code worldId}, or null when it is not a valid id. */
	public static ResourceKey<Level> parse(String worldId) {
		if (worldId == null || worldId.isBlank()) {
			return null;
		}

		try {
			return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(worldId.trim()));
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	/** The id a dimension key carries, in the form {@link #parse} reads back. */
	public static String name(ResourceKey<Level> dimension) {
		return dimension == null ? "" : dimension.location().toString();
	}
}
