package dev.belikhun.luna.core.fabric.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;

/**
 * The handful of world readings that moved in the 26.x line, for Minecraft 26.1
 * and up.
 *
 * The 1.21 module's copy of this class reads the same three things through the
 * API that line still has; see it for what moved and why a runtime guard cannot
 * cover any of it.
 */
public final class WorldFacts {
	private static final long TICKS_PER_DAY = 24000L;

	private WorldFacts() {
	}

	/**
	 * A level's dimension id, e.g. {@code minecraft:the_nether}, and its path
	 * alone, e.g. {@code the_nether}. Blank when the level cannot say.
	 */
	public static String dimensionId(ServerLevel level) {
		ResourceKey<Level> dimension = level == null ? null : level.dimension();

		return dimension == null || dimension.identifier() == null ? "" : dimension.identifier().toString();
	}

	public static String dimensionPath(ServerLevel level) {
		ResourceKey<Level> dimension = level == null ? null : level.dimension();

		return dimension == null || dimension.identifier() == null ? "" : dimension.identifier().getPath();
	}

	/** The biome the player is standing in, by its path alone. */
	public static String biomePath(ServerPlayer player) {
		return levelOf(player).getBiome(player.blockPosition()).unwrapKey()
			.map(key -> key.identifier().getPath())
			.orElse("unknown");
	}

	/** The level a player is standing in. */
	public static ServerLevel levelOf(ServerPlayer player) {
		return player.level();
	}

	/**
	 * Ticks into the current day, 0 to 23999.
	 *
	 * 26.x replaced the level's own day time with a registry of world clocks, so
	 * the overworld clock has to be looked up rather than read. A world whose
	 * clock is missing falls back to the raw game time, which is the same number
	 * on any world that has not had its time set.
	 */
	public static long dayTimeTicks(ServerLevel level) {
		return Guarded.value(
			() -> Math.floorMod(
				level.clockManager().getTotalTicks(
					level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD)
				),
				TICKS_PER_DAY
			),
			Math.floorMod(level.getGameTime(), TICKS_PER_DAY)
		);
	}

	/**
	 * How long the current weather still has to run, in ticks.
	 *
	 * 26.x moved the weather timers into saved data the level does not expose, so
	 * this line reports zero, which the placeholder renders the same way it does
	 * a weather change that has only just happened.
	 */
	public static long weatherDurationTicks(ServerLevel level, boolean raining, boolean thundering) {
		return 0L;
	}
}
