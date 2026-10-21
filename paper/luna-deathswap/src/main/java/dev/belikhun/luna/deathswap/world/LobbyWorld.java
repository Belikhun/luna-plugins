package dev.belikhun.luna.deathswap.world;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * The lobby: an empty (void) world of its own, kept loaded for good, with
 * time, weather and mobs frozen. Lives at lunadeathswap:lobby, so it never
 * collides with round worlds and is never swept up by their cleanup.
 */
public final class LobbyWorld {
	/** Key path; Paper names the world "lunadeathswap_lobby", outside the round-world prefix. */
	public static final String LEVEL_NAME = "lobby";

	private LobbyWorld() {
	}

	public static NamespacedKey key() {
		return new NamespacedKey(RoundWorldService.NAMESPACE, LEVEL_NAME);
	}

	/** Load the lobby world, creating it empty the first time. */
	public static World ensure(LunaLogger logger) {
		World existing = Bukkit.getWorld(key());
		if (existing != null) {
			return existing;
		}

		World world = WorldCreator.ofKey(key())
			.environment(World.Environment.NORMAL)
			.generator(new VoidGenerator())
			.biomeProvider(new PlainsBiome())
			.generateStructures(false)
			.createWorld();
		if (world == null) {
			logger.error("Could not create the lobby world.");
			return null;
		}

		world.setDifficulty(Difficulty.PEACEFUL);
		world.setSpawnLocation(0, 65, 0);
		world.setTime(6000L);
		world.setStorm(false);
		world.setThundering(false);

		// gamerule names changed in 1.21.11/26.x; console commands take the live names
		String dimension = key().toString();
		for (String rule : List.of("advance_time false", "advance_weather false", "spawn_mobs false",
			"spawn_monsters false", "spawn_phantoms false", "spawn_patrols false", "spawn_wandering_traders false",
			"fall_damage false", "fire_damage false", "drowning_damage false", "freeze_damage false",
			"immediate_respawn true", "show_death_messages false", "keep_inventory true", "mob_griefing false")) {
			try {
				Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in " + dimension + " run gamerule " + rule);
			} catch (RuntimeException ignored) {
				// an unknown rule on this version is fine to skip
			}
		}
		return world;
	}

	public static Location defaultSpawn(World world) {
		return new Location(world, 0.5, 65, 0.5, 180f, 0f);
	}

	/** Generates nothing at all. */
	public static final class VoidGenerator extends ChunkGenerator {
		@Override
		public boolean shouldGenerateNoise() {
			return false;
		}

		@Override
		public boolean shouldGenerateSurface() {
			return false;
		}

		@Override
		public boolean shouldGenerateCaves() {
			return false;
		}

		@Override
		public boolean shouldGenerateDecorations() {
			return false;
		}

		@Override
		public boolean shouldGenerateMobs() {
			return false;
		}

		@Override
		public boolean shouldGenerateStructures() {
			return false;
		}

		@Override
		public Location getFixedSpawnLocation(World world, Random random) {
			return defaultSpawn(world);
		}
	}

	/** One biome everywhere, so the void has a calm sky and no rain. */
	public static final class PlainsBiome extends BiomeProvider {
		@Override
		public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
			return Biome.PLAINS;
		}

		@Override
		public List<Biome> getBiomes(WorldInfo worldInfo) {
			return List.of(Biome.PLAINS);
		}
	}
}
