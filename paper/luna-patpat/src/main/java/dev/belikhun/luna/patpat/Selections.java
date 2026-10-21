package dev.belikhun.luna.patpat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who uses which animation, saved in {@code selections.yml}.
 *
 * <ul>
 *   <li>incoming: plays when anyone pats this player</li>
 *   <li>outgoing: plays when this player pats another player</li>
 *   <li>pair: plays when one particular player pats another (wins over both of the above)</li>
 *   <li>default: plays on every pat nobody chose anything for</li>
 * </ul>
 */
final class Selections {
	enum Kind { INCOMING, OUTGOING }

	static final class PlayerChoice {
		final UUID uuid;
		String name;
		String incoming;
		String outgoing;

		PlayerChoice(UUID uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}

		boolean empty() {
			return incoming == null && outgoing == null;
		}
	}

	record Pair(UUID from, String fromName, UUID to, String toName, String animation) {
	}

	private final File file;
	private final Map<UUID, PlayerChoice> players = new LinkedHashMap<>();
	private final List<Pair> pairs = new ArrayList<>();
	private String defaultAnimation;

	Selections(File file) {
		this.file = file;
	}

	Collection<PlayerChoice> players() {
		return players.values();
	}

	List<Pair> pairs() {
		return pairs;
	}

	String defaultAnimation() {
		return defaultAnimation;
	}

	PlayerChoice get(UUID uuid) {
		return players.get(uuid);
	}

	void set(UUID uuid, String name, Kind kind, String animation) {
		PlayerChoice c = players.computeIfAbsent(uuid, u -> new PlayerChoice(u, name));
		c.name = name;
		if (kind == Kind.INCOMING) c.incoming = animation; else c.outgoing = animation;
		if (c.empty()) players.remove(uuid);
	}

	void setPair(UUID from, String fromName, UUID to, String toName, String animation) {
		pairs.removeIf(p -> p.from().equals(from) && p.to().equals(to));
		if (animation != null) {
			pairs.add(new Pair(from, fromName, to, toName, animation));
		}
	}

	void setDefault(String animation) {
		this.defaultAnimation = animation;
	}

	/** Drops every choice of an animation that is no longer in the library. */
	void forget(String animation) {
		for (PlayerChoice c : new ArrayList<>(players.values())) {
			if (animation.equals(c.incoming)) c.incoming = null;
			if (animation.equals(c.outgoing)) c.outgoing = null;
			if (c.empty()) players.remove(c.uuid);
		}
		pairs.removeIf(p -> p.animation().equals(animation));
		if (animation.equals(defaultAnimation)) defaultAnimation = null;
	}

	void load() {
		players.clear();
		pairs.clear();
		if (!file.exists()) {
			return;
		}
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		defaultAnimation = y.getString("default");
		ConfigurationSection ps = y.getConfigurationSection("players");
		if (ps != null) {
			for (String key : ps.getKeys(false)) {
				ConfigurationSection s = ps.getConfigurationSection(key);
				if (s == null) continue;
				PlayerChoice c = new PlayerChoice(UUID.fromString(key), s.getString("name", key));
				c.incoming = s.getString("incoming");
				c.outgoing = s.getString("outgoing");
				if (!c.empty()) players.put(c.uuid, c);
			}
		}
		for (Map<?, ?> m : y.getMapList("pairs")) {
			try {
				pairs.add(new Pair(
					UUID.fromString(String.valueOf(m.get("from"))), String.valueOf(m.get("fromName")),
					UUID.fromString(String.valueOf(m.get("to"))), String.valueOf(m.get("toName")),
					String.valueOf(m.get("animation"))));
			} catch (IllegalArgumentException ignored) {
				// skip a broken entry
			}
		}
	}

	void save() throws IOException {
		YamlConfiguration y = new YamlConfiguration();
		y.set("default", defaultAnimation);
		for (PlayerChoice c : players.values()) {
			String k = "players." + c.uuid;
			y.set(k + ".name", c.name);
			y.set(k + ".incoming", c.incoming);
			y.set(k + ".outgoing", c.outgoing);
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (Pair p : pairs) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("from", p.from().toString());
			m.put("fromName", p.fromName());
			m.put("to", p.to().toString());
			m.put("toName", p.toName());
			m.put("animation", p.animation());
			list.add(m);
		}
		y.set("pairs", list);
		y.save(file);
	}
}
