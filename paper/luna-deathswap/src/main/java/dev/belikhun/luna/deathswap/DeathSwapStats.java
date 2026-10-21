package dev.belikhun.luna.deathswap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lifetime numbers per player (rounds, wins, traps), kept in stats.yml next
 * to the config. Small enough to hold in memory and write whole after each round.
 */
public final class DeathSwapStats {
	public static final class Entry {
		public String name = "";
		public int games;
		public int wins;
		public int traps;
		public int deaths;
		public long longestSurviveSeconds;
	}

	private final JavaPlugin plugin;
	private final File file;
	private final Map<UUID, Entry> entries = new HashMap<>();

	public DeathSwapStats(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "stats.yml");
	}

	public void load() {
		entries.clear();
		if (!file.exists()) {
			return;
		}

		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection players = yaml.getConfigurationSection("players");
		if (players == null) {
			return;
		}

		for (String key : players.getKeys(false)) {
			ConfigurationSection section = players.getConfigurationSection(key);
			if (section == null) {
				continue;
			}

			try {
				Entry entry = new Entry();
				entry.name = section.getString("name", "");
				entry.games = section.getInt("games");
				entry.wins = section.getInt("wins");
				entry.traps = section.getInt("traps");
				entry.deaths = section.getInt("deaths");
				entry.longestSurviveSeconds = section.getLong("longest-survive-seconds");
				entries.put(UUID.fromString(key), entry);
			} catch (IllegalArgumentException ignored) {
				// not a uuid key; skip it
			}
		}
	}

	/** The best players by wins, then games, then traps. Only players who have played. */
	public java.util.List<Map.Entry<UUID, Entry>> top(int limit) {
		return entries.entrySet().stream()
			.filter(item -> item.getValue().games > 0)
			.sorted(java.util.Comparator.<Map.Entry<UUID, Entry>>comparingInt(item -> item.getValue().wins).reversed()
				.thenComparing(java.util.Comparator.<Map.Entry<UUID, Entry>>comparingInt(item -> item.getValue().games).reversed())
				.thenComparing(java.util.Comparator.<Map.Entry<UUID, Entry>>comparingInt(item -> item.getValue().traps).reversed()))
			.limit(Math.max(0, limit))
			.toList();
	}

	/** 1-based rank by the same order as {@link #top(int)}, or 0 when unranked. */
	public int rankOf(UUID playerId) {
		java.util.List<Map.Entry<UUID, Entry>> all = top(Integer.MAX_VALUE);
		for (int index = 0; index < all.size(); index++) {
			if (all.get(index).getKey().equals(playerId)) {
				return index + 1;
			}
		}
		return 0;
	}

	public Entry get(UUID playerId) {
		return entries.computeIfAbsent(playerId, id -> new Entry());
	}

	/** Snapshot on the main thread, write off it. */
	public void saveAsync() {
		YamlConfiguration yaml = snapshot();
		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(yaml));
	}

	public void saveNow() {
		write(snapshot());
	}

	private YamlConfiguration snapshot() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<UUID, Entry> item : entries.entrySet()) {
			String path = "players." + item.getKey();
			Entry entry = item.getValue();
			yaml.set(path + ".name", entry.name);
			yaml.set(path + ".games", entry.games);
			yaml.set(path + ".wins", entry.wins);
			yaml.set(path + ".traps", entry.traps);
			yaml.set(path + ".deaths", entry.deaths);
			yaml.set(path + ".longest-survive-seconds", entry.longestSurviveSeconds);
		}
		return yaml;
	}

	private synchronized void write(YamlConfiguration yaml) {
		try {
			yaml.save(file);
		} catch (IOException exception) {
			plugin.getLogger().warning("Could not save stats.yml: " + exception.getMessage());
		}
	}
}
