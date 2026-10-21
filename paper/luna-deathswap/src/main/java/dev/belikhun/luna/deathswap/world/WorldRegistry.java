package dev.belikhun.luna.deathswap.world;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Every world this plugin ever made, and what became of it, in worlds.yml.
 *
 * This is what makes cleanup trustworthy: a world is written here before its
 * first file exists and removed only once its last file is gone, so a crash,
 * a kill or a failed delete at any point leaves an entry the next start
 * finishes off. Manhunt's queue only learnt about a world when a delete had
 * already failed, and lost it when Multiverse did not know the name.
 */
public final class WorldRegistry {
	public enum State {
		/** The generator is writing it. */
		GENERATING,
		/** Finished and waiting in the pool, not loaded. */
		POOLED,
		/** Loaded and in use by a round. */
		ACTIVE,
		/** Being removed; finished on the next attempt or start. */
		DELETING
	}

	public static final class Entry {
		public final String id;
		public State state;
		public long seed;
		public long createdAt;
		public int deleteAttempts;

		Entry(String id, State state, long seed, long createdAt) {
			this.id = id;
			this.state = state;
			this.seed = seed;
			this.createdAt = createdAt;
		}
	}

	private final File file;
	private final Logger logger;
	private final Map<String, Entry> entries = new LinkedHashMap<>();

	public WorldRegistry(File file, Logger logger) {
		this.file = file;
		this.logger = logger;
	}

	public synchronized void load() {
		entries.clear();
		if (!file.exists()) {
			return;
		}

		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection worlds = yaml.getConfigurationSection("worlds");
		if (worlds == null) {
			return;
		}

		for (String id : worlds.getKeys(false)) {
			ConfigurationSection section = worlds.getConfigurationSection(id);
			if (section == null) {
				continue;
			}
			State state;
			try {
				state = State.valueOf(section.getString("state", "DELETING").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException exception) {
				state = State.DELETING;
			}
			Entry entry = new Entry(id, state, section.getLong("seed"), section.getLong("created-at"));
			entry.deleteAttempts = section.getInt("delete-attempts");
			entries.put(id, entry);
		}
	}

	public synchronized Entry put(String id, State state, long seed) {
		Entry entry = new Entry(id, state, seed, System.currentTimeMillis());
		entries.put(id, entry);
		save();
		return entry;
	}

	public synchronized void setState(String id, State state) {
		Entry entry = entries.get(id);
		if (entry != null && entry.state != state) {
			entry.state = state;
			save();
		}
	}

	public synchronized void noteDeleteAttempt(String id) {
		Entry entry = entries.get(id);
		if (entry != null) {
			entry.deleteAttempts++;
			save();
		}
	}

	public synchronized void remove(String id) {
		if (entries.remove(id) != null) {
			save();
		}
	}

	public synchronized Entry get(String id) {
		return entries.get(id);
	}

	public synchronized boolean contains(String id) {
		return entries.containsKey(id);
	}

	public synchronized List<Entry> withState(State state) {
		List<Entry> list = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.state == state) {
				list.add(entry);
			}
		}
		return list;
	}

	public synchronized List<Entry> all() {
		return new ArrayList<>(entries.values());
	}

	private void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.options().setHeader(List.of(
			"Worlds LunaDeathSwap owns. Managed by the plugin; do not edit while the server runs."
		));
		for (Entry entry : entries.values()) {
			String path = "worlds." + entry.id;
			yaml.set(path + ".state", entry.state.name());
			yaml.set(path + ".seed", entry.seed);
			yaml.set(path + ".created-at", entry.createdAt);
			if (entry.deleteAttempts > 0) {
				yaml.set(path + ".delete-attempts", entry.deleteAttempts);
			}
		}
		try {
			File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
			yaml.save(tmp);
			if (!tmp.renameTo(file)) {
				yaml.save(file);
				tmp.delete();
			}
		} catch (IOException exception) {
			logger.warning("Could not save worlds.yml: " + exception.getMessage());
		}
	}
}
