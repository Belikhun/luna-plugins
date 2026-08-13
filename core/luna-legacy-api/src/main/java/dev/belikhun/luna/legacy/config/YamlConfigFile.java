package dev.belikhun.luna.legacy.config;

import dev.belikhun.luna.legacy.string.Strings;

import dev.belikhun.luna.legacy.exception.ConfigStoreException;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;

/**
 * A yaml file read by path, for the platforms that have no Bukkit under them.
 *
 * {@link ConfigStore} is the same idea for Paper, and it cannot serve here: it
 * takes a {@code Plugin} and hands back Bukkit's {@code YamlConfiguration}.
 * This one is built on {@link LunaYamlConfig}, which already speaks both
 * snakeyaml and Bukkit's loader, so a mod and a plugin read the operator's file
 * identically and the modules above them share their defaults.
 *
 * Paths are dotted, as everywhere else in luna: {@code get("database.host")}.
 */
public final class YamlConfigFile {
	private final Path file;
	private Map<String, Object> root;

	private YamlConfigFile(Path file, Map<String, Object> root) {
		this.file = file;
		this.root = root;
	}

	/**
	 * Load the file, creating it from the jar's own copy of {@code resourceName}
	 * when it is not there yet, and filling in any key the shipped defaults have
	 * and the operator's file does not.
	 *
	 * The merge is what lets a new setting reach an existing server without the
	 * operator deleting their config: missing keys are written back, present ones
	 * are left exactly as they were.
	 *
	 * @param file the file on disk, created together with its parent directories
	 * @param resourceAnchor a class from the jar carrying the default resource
	 * @param resourceName the default's path inside that jar
	 */
	public static YamlConfigFile load(Path file, Class<?> resourceAnchor, String resourceName) {
		try {
			LunaYamlConfig.ensureFile(file, () -> resourceAnchor.getClassLoader().getResourceAsStream(resourceName));

			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(file));
			Map<String, Object> defaults = loadDefaults(resourceAnchor, resourceName);
			if (!defaults.isEmpty() && LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(file, current);
			}

			return new YamlConfigFile(file, current);
		} catch (RuntimeException exception) {
			throw new ConfigStoreException("Không thể nạp cấu hình: " + file, exception);
		}
	}

	/** Load a file with no shipped defaults behind it; a missing file reads as empty. */
	public static YamlConfigFile loadOrEmpty(Path file) {
		return new YamlConfigFile(file, new LinkedHashMap<>(LunaYamlConfig.loadMap(file)));
	}

	/**
	 * An empty document bound to a path, for a caller that rewrites the whole file
	 * rather than editing it. Reading the old contents first only to discard them
	 * is what this avoids.
	 */
	public static YamlConfigFile empty(Path file) {
		return new YamlConfigFile(file, new LinkedHashMap<>());
	}

	public Path path() {
		return file;
	}

	public Map<String, Object> raw() {
		return root;
	}

	public Object get(String path) {
		return ConfigValues.resolve(root, path);
	}

	public String getString(String path, String fallback) {
		return ConfigValues.string(get(path), fallback);
	}

	public int getInt(String path, int fallback) {
		return ConfigValues.intValue(get(path), fallback);
	}

	public long getLong(String path, long fallback) {
		return ConfigValues.longValue(get(path), fallback);
	}

	public double getDouble(String path, double fallback) {
		return ConfigValues.doubleValue(get(path), fallback);
	}

	public boolean getBoolean(String path, boolean fallback) {
		return ConfigValues.booleanValue(get(path), fallback);
	}

	public List<String> getStringList(String path) {
		return ConfigValues.stringList(get(path));
	}

	/** The nested map at this path, empty when the path is absent or holds a leaf. */
	public Map<String, Object> section(String path) {
		return ConfigValues.map(get(path));
	}

	/** The immediate child keys of a section, in the order the file lists them. */
	public List<String> keys(String path) {
		return new ArrayList<>(section(path).keySet());
	}

	/**
	 * Write a value at a dotted path, creating the sections along the way.
	 *
	 * Nothing reaches disk until {@link #save()}, so a caller changing several
	 * keys writes the file once.
	 */
	@SuppressWarnings("unchecked")
	public YamlConfigFile set(String path, Object value) {
		if (path == null || Strings.isBlank(path)) {
			return this;
		}

		String[] segments = path.trim().split("\\.");
		Map<String, Object> node = root;

		for (int index = 0; index < segments.length - 1; index++) {
			Object child = node.get(segments[index]);

			if (child instanceof Map<?, ?>) {
				Map<?, ?> existing = (Map<?, ?>) child;

				node = (Map<String, Object>) existing;
				continue;
			}

			Map<String, Object> created = new LinkedHashMap<>();
			node.put(segments[index], created);
			node = created;
		}

		String leaf = segments[segments.length - 1];
		if (value == null) {
			node.remove(leaf);
			return this;
		}

		node.put(leaf, value);
		return this;
	}

	public YamlConfigFile remove(String path) {
		return set(path, null);
	}

	public void save() {
		LunaYamlConfig.dumpMap(file, root);
	}

	public void reload() {
		root = new LinkedHashMap<>(LunaYamlConfig.loadMap(file));
	}

	private static Map<String, Object> loadDefaults(Class<?> resourceAnchor, String resourceName) {
		try (InputStream stream = resourceAnchor.getClassLoader().getResourceAsStream(resourceName)) {
			if (stream == null) {
				return Collections.<String, Object>emptyMap();
			}

			return LunaYamlConfig.loadMap(stream);
		} catch (Exception ignored) {
			return Collections.<String, Object>emptyMap();
		}
	}
}
