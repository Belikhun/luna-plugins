package dev.belikhun.luna.core.fabric.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Turning a world id back into a dimension key, as the 26.x line spells it.
 *
 * The registry key type itself is stable; only the identifier class it is built
 * from was renamed, which is the same one-method split {@link ItemLookup} is. The
 * 1.20-1.21 copy lives in luna-core-fabric/src/mc21.
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
			return ResourceKey.create(Registries.DIMENSION, Identifier.parse(worldId.trim()));
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	/** The id a dimension key carries, in the form {@link #parse} reads back. */
	public static String name(ResourceKey<Level> dimension) {
		return dimension == null ? "" : dimension.identifier().toString();
	}
}
