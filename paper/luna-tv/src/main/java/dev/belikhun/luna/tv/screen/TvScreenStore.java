package dev.belikhun.luna.tv.screen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import dev.belikhun.luna.core.api.logging.LunaLogger;

/**
 * Reads and writes screens.yml.
 *
 * Snapshotting happens on the caller's thread (it only touches the model) and
 * the file write is handed to an async task, because a screen mutation is a
 * command response and must not wait on a disk.
 */
public final class TvScreenStore {

	private static final String FILE_NAME = "screens.yml";

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final File file;

	public TvScreenStore(JavaPlugin plugin, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger;
		this.file = new File(plugin.getDataFolder(), FILE_NAME);
	}

	/**
	 * Loads every remembered screen.
	 *
	 * A malformed entry is skipped with a warning rather than aborting the load:
	 * one bad screen must not cost the operator the others.
	 *
	 * @return the screens found on disk, in file order
	 */
	public List<TvScreen> load() {
		List<TvScreen> screens = new ArrayList<>();

		if (!file.isFile()) {
			return screens;
		}

		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection root = yaml.getConfigurationSection("screens");

		if (root == null) {
			return screens;
		}

		for (String name : root.getKeys(false)) {
			ConfigurationSection section = root.getConfigurationSection(name);

			if (section == null) {
				continue;
			}

			try {
				screens.add(read(name, section));
			} catch (Exception exception) {
				logger.warn("Bỏ qua màn hình '" + name + "' trong screens.yml: " + exception.getMessage());
			}
		}

		return screens;
	}

	private static TvScreen read(String name, ConfigurationSection section) {
		String world = section.getString("world");

		if (world == null || world.isBlank()) {
			throw new IllegalArgumentException("thiếu world");
		}

		BlockFace facing = BlockFace.valueOf(
			section.getString("facing", "NORTH").toUpperCase(Locale.ROOT));

		TvScreen screen = new TvScreen(
			name,
			world,
			vector(section, "cornerA"),
			vector(section, "cornerB"),
			facing,
			section.getString("url", ""),
			section.getInt("volume", 100),
			section.getBoolean("locked", false),
			section.getBoolean("audio", false),
			section.getInt("scale", 1),
			section.getInt("fps", 0),
			section.getInt("max-megabits", 0),
			section.getInt("brightness", 100),
			section.getString("converter", ""),
			section.getString("dither-pattern", ""),
			section.getBoolean("stereo", false),
			section.getBoolean("scroll", true),
			section.getString("createdBy", "?"),
			section.getLong("createdAt", 0L));

		if (section.contains("redstone")) {
			screen.redstone(
				section.getString("redstone.world", world),
				new BlockVector(
					section.getInt("redstone.x"),
					section.getInt("redstone.y"),
					section.getInt("redstone.z")));
		}

		return screen;
	}

	private static BlockVector vector(ConfigurationSection section, String key) {
		ConfigurationSection at = section.getConfigurationSection(key);

		if (at == null) {
			throw new IllegalArgumentException("thiếu " + key);
		}

		return new BlockVector(at.getInt("x"), at.getInt("y"), at.getInt("z"));
	}

	/**
	 * Writes the given screens, replacing the file's contents.
	 *
	 * @param screens every screen that should exist on disk
	 * @param async true to write on an async task, false to write inline (used
	 *              at shutdown, where the scheduler is already closing)
	 */
	public void save(List<TvScreen> screens, boolean async) {
		YamlConfiguration yaml = new YamlConfiguration();

		for (TvScreen screen : screens) {
			String base = "screens." + screen.name();

			yaml.set(base + ".world", screen.world());
			yaml.set(base + ".facing", screen.facing().name());
			yaml.set(base + ".url", screen.url());
			yaml.set(base + ".volume", screen.volume());
			yaml.set(base + ".locked", screen.locked());
			yaml.set(base + ".audio", screen.audio());
			yaml.set(base + ".scale", screen.scale());
			yaml.set(base + ".fps", screen.fps());
			yaml.set(base + ".max-megabits", screen.maxMegabits());
			yaml.set(base + ".brightness", screen.brightness());
			yaml.set(base + ".converter", screen.converter());
			yaml.set(base + ".dither-pattern", screen.ditherPattern());
			yaml.set(base + ".stereo", screen.stereo());
			yaml.set(base + ".scroll", screen.scroll());
			yaml.set(base + ".createdBy", screen.createdBy());
			yaml.set(base + ".createdAt", screen.createdAt());
			writeVector(yaml, base + ".cornerA", screen.cornerA());
			writeVector(yaml, base + ".cornerB", screen.cornerB());
		}

		if (!async) {
			write(yaml);
			return;
		}

		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(yaml));
	}

	private static void writeVector(YamlConfiguration yaml, String base, BlockVector vector) {
		yaml.set(base + ".x", vector.getBlockX());
		yaml.set(base + ".y", vector.getBlockY());
		yaml.set(base + ".z", vector.getBlockZ());
	}

	private void write(YamlConfiguration yaml) {
		try {
			yaml.save(file);
		} catch (IOException exception) {
			logger.error("Không lưu được screens.yml.", exception);
		}
	}
}
