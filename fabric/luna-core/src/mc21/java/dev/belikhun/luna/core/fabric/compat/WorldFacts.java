package dev.belikhun.luna.core.fabric.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * The handful of world readings that moved in the 26.x line, for Minecraft 1.20
 * through 1.21.x.
 *
 * Three separate changes land here, none of which a runtime guard can absorb
 * because each one is a missing symbol at compile time:
 *
 * <ul>
 *   <li>{@code ServerPlayer.serverLevel()} became a covariant {@code level()};</li>
 *   <li>{@code Level.getDayTime()} was replaced by the world-clock registry;</li>
 *   <li>{@code ServerLevel.getLevelData()} is no longer public, and the weather
 *       timers it exposed moved into saved data.</li>
 * </ul>
 *
 * The sibling module supplies its own copy of this class. Everything else in the
 * placeholder service reads the game through here and stays version-blind.
 */
public final class WorldFacts {
	private WorldFacts() {
	}

	/**
	 * A level's dimension id, e.g. {@code minecraft:the_nether}, and its path
	 * alone, e.g. {@code the_nether}. Blank when the level cannot say.
	 */
	public static String dimensionId(ServerLevel level) {
		ResourceKey<Level> dimension = level == null ? null : level.dimension();

		return dimension == null || dimension.location() == null ? "" : dimension.location().toString();
	}

	public static String dimensionPath(ServerLevel level) {
		ResourceKey<Level> dimension = level == null ? null : level.dimension();

		return dimension == null || dimension.location() == null ? "" : dimension.location().getPath();
	}

	/** The biome the player is standing in, by its path alone. */
	public static String biomePath(ServerPlayer player) {
		return levelOf(player).getBiome(player.blockPosition()).unwrapKey()
			.map(key -> key.location().getPath())
			.orElse("unknown");
	}

	/** The level a player is standing in. */
	public static ServerLevel levelOf(ServerPlayer player) {
		return player.serverLevel();
	}

	/** Ticks into the current day, 0 to 23999. */
	public static long dayTimeTicks(ServerLevel level) {
		return Math.floorMod(level.getDayTime(), 24000L);
	}

	/**
	 * How long the current weather still has to run, in ticks.
	 *
	 * Zero when the level cannot say, which reads as "just changed" rather than
	 * as an error, and is what the placeholder shows anyway.
	 */
	public static long weatherDurationTicks(ServerLevel level, boolean raining, boolean thundering) {
		if (!(level.getLevelData() instanceof ServerLevelData levelData)) {
			return 0L;
		}

		if (thundering) {
			return Math.max(0, levelData.getThunderTime());
		}

		if (raining) {
			return Math.max(0, levelData.getRainTime());
		}

		return Math.max(0, levelData.getClearWeatherTime());
	}
}
