package dev.belikhun.luna.tv.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Light;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.TvScreen;

/**
 * Makes a screen light the room it is in.
 *
 * No render type can do this. A picture drawn full bright is bright in itself and
 * leaves everything around it black, which is what a screen in a dark room does
 * not look like. Real light means real light sources, so this lays a sparse grid
 * of {@code minecraft:light} blocks in the air in front of a running screen and
 * takes them away again when it stops. Being world blocks, they light the wall
 * for players without the mod as well.
 *
 * Two rules keep it from ever damaging a build: nothing is placed except into air
 * that is already there, and every position placed is remembered, so removal puts
 * back exactly what was taken and nothing else.
 */
public final class LightHalo {

	/** One light every this many blocks across the wall; 15 reaches far enough. */
	private static final int STEP = 4;

	private final JavaPlugin plugin;
	private final LunaLogger logger;

	/** Exactly what this plugin placed, per screen, so removal is exact. */
	private final Map<String, List<BlockVector>> placed = new HashMap<>();

	public LightHalo(JavaPlugin plugin, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger;
	}

	/**
	 * Lights the room in front of a screen.
	 *
	 * @param instance the screen, which must be running
	 */
	public void apply(ScreenInstance instance) {
		TvScreen screen = instance.screen();

		clear(instance);

		int level = level(screen.glow());

		if (level <= 0) {
			return;
		}

		World world = plugin.getServer().getWorld(screen.world());

		if (world == null) {
			return;
		}

		BlockFace facing = screen.facing();
		BlockVector a = screen.cornerA();
		BlockVector b = screen.cornerB();
		List<BlockVector> mine = new ArrayList<>();

		for (int x = min(a.getBlockX(), b.getBlockX()); x <= max(a.getBlockX(), b.getBlockX()); x += STEP) {
			for (int y = min(a.getBlockY(), b.getBlockY()); y <= max(a.getBlockY(), b.getBlockY()); y += STEP) {
				for (int z = min(a.getBlockZ(), b.getBlockZ()); z <= max(a.getBlockZ(), b.getBlockZ()); z += STEP) {
					place(world, x + facing.getModX(), y + facing.getModY(), z + facing.getModZ(),
						level, mine);
				}
			}
		}

		if (!mine.isEmpty()) {
			placed.put(instance.name(), mine);
		}
	}

	private void place(World world, int x, int y, int z, int level, List<BlockVector> mine) {
		if (!world.isChunkLoaded(x >> 4, z >> 4)) {
			return;
		}

		Block block = world.getBlockAt(x, y, z);

		// Air only. A screen is often built into a wall somebody spent an evening
		// on, and a light block that replaced part of it would be indistinguishable
		// from the wall having been griefed.
		if (block.getType() != Material.AIR && block.getType() != Material.CAVE_AIR) {
			return;
		}

		Light light = (Light) Material.LIGHT.createBlockData();

		light.setLevel(level);
		block.setBlockData(light, false);
		mine.add(new BlockVector(x, y, z));
	}

	/**
	 * Takes a screen's lights away again.
	 *
	 * @param instance the screen
	 */
	public void clear(ScreenInstance instance) {
		remove(instance.name());
	}

	/** Takes every screen's lights away; for shutdown. */
	public void clearAll() {
		for (String name : List.copyOf(placed.keySet())) {
			remove(name);
		}
	}

	private void remove(String name) {
		List<BlockVector> mine = placed.remove(name);

		if (mine == null) {
			return;
		}

		for (BlockVector at : mine) {
			World world = null;

			for (World candidate : plugin.getServer().getWorlds()) {
				Block block = candidate.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ());

				if (block.getType() == Material.LIGHT) {
					world = candidate;
					break;
				}
			}

			if (world == null) {
				continue;
			}

			// only ours: a light block somebody placed themselves in the meantime
			// is theirs, and the type check above is the whole of that promise
			world.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ())
				.setType(Material.AIR, false);
		}
	}

	/** Glow percent to a block light level; 0 leaves the room dark. */
	private static int level(int glow) {
		if (glow <= 0) {
			return 0;
		}

		return Math.max(1, Math.min(15, Math.round(glow * 15.0f / 100.0f)));
	}

	private static int min(int a, int b) {
		return Math.min(a, b);
	}

	private static int max(int a, int b) {
		return Math.max(a, b);
	}

	/** Where the lights of one screen are, for diagnostics. */
	public int count(String name) {
		List<BlockVector> mine = placed.get(name);

		return mine == null ? 0 : mine.size();
	}
}
