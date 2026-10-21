package dev.belikhun.luna.deathswap;

import dev.belikhun.luna.deathswap.room.RoomSettings;
import dev.belikhun.luna.deathswap.world.RoundWorldService;
import dev.belikhun.luna.deathswap.world.SpawnPregen;
import dev.belikhun.luna.deathswap.world.WorldGenerator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The global half of config.yml, read once per load. Per-room settings start
 * from {@link #roomDefaults()} and then live in rooms.yml.
 */
public record DeathSwapSettings(
	RoomSettings roomDefaults,
	String defaultRoom,
	String defaultRoomName,
	boolean autoJoin,
	String playerNameFormat,
	String footer,
	boolean scoreboardEnabled,
	int scoreboardUpdateTicks,
	Map<String, Template> templates,
	Map<String, String> actionbar,
	RoundWorldService.Settings worlds,
	WorldGenerator.Options generator,
	SpawnPregen.Options pregen
) {
	/** One sidebar layout: a title, and lines with an optional repeated block in the middle. */
	public record Template(String title, List<String> header, String aliveLine, String aliveEmpty, int aliveMax, List<String> footer) {
	}

	public static DeathSwapSettings load(FileConfiguration config) {
		RoomSettings defaults = new RoomSettings().load(config.getConfigurationSection("room-defaults"));

		Map<String, Template> templates = new LinkedHashMap<>();
		ConfigurationSection templateRoot = config.getConfigurationSection("scoreboard.templates");
		if (templateRoot != null) {
			for (String key : templateRoot.getKeys(false)) {
				ConfigurationSection section = templateRoot.getConfigurationSection(key);
				if (section == null) {
					continue;
				}
				List<String> header = new ArrayList<>(section.getStringList("header"));
				header.addAll(section.getStringList("lines"));
				String repeated = section.getString("alive_line", section.getString("room_line", ""));
				String repeatedEmpty = section.getString("alive_empty", section.getString("room_empty", ""));
				templates.put(key, new Template(
					section.getString("title", "DEATHSWAP"),
					header,
					repeated,
					repeatedEmpty,
					Math.max(0, section.getInt("alive_max", section.getInt("room_max", 6))),
					section.getStringList("footer")
				));
			}
		}

		Map<String, String> actionbar = new LinkedHashMap<>();
		ConfigurationSection actionbarRoot = config.getConfigurationSection("actionbar");
		if (actionbarRoot != null) {
			for (String key : actionbarRoot.getKeys(false)) {
				if (actionbarRoot.isString(key)) {
					actionbar.put(key, actionbarRoot.getString(key));
				}
			}
		}

		return new DeathSwapSettings(
			defaults,
			config.getString("rooms.default", "main").toLowerCase(Locale.ROOT),
			config.getString("rooms.default-name", "Phòng chính"),
			config.getBoolean("rooms.auto-join", true),
			config.getString("ui.player-name-format", "<white>%player_name%</white>"),
			config.getString("ui.footer", " <gray>deathswap "),
			config.getBoolean("scoreboard.enabled", true),
			Math.max(2, config.getInt("scoreboard.update-ticks", 10)),
			templates,
			actionbar,
			new RoundWorldService.Settings(
				config.getBoolean("worlds.use-generator", true),
				Math.max(0, Math.min(3, config.getInt("worlds.pool-size", 1))),
				Math.max(1, config.getInt("worlds.delete-retries", 5))
			),
			new WorldGenerator.Options(
				config.getString("worlds.generator.java", ""),
				config.getString("worlds.generator.server-jar", ""),
				Math.max(512, config.getInt("worlds.generator.memory-mb", 1536)),
				Math.max(60, config.getInt("worlds.generator.timeout-seconds", 240))
			),
			new SpawnPregen.Options(
				Math.max(0, Math.min(512, config.getInt("worlds.spawn-pregen.radius", 160))),
				Math.max(10, config.getInt("worlds.spawn-pregen.timeout-seconds", 90))
			)
		);
	}
}
