package dev.belikhun.luna.legacy.config;

import dev.belikhun.luna.legacy.exception.ConfigStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reading and writing luna's yaml, without importing snakeyaml.
 *
 * The reflection is not caution, it is a requirement: every mod platform relocates
 * snakeyaml into its own jar under `dev.belikhun.luna.shadow.snakeyaml`, so a direct
 * import would resolve at compile time and vanish at runtime. Both names are tried in
 * turn, exactly as the modern api does.
 *
 * The Bukkit `YamlConfiguration` path the modern api probes for is **not** here. It
 * exists there so a Paper plugin keeps Bukkit's own yaml semantics; no forge server
 * has that class, so on this line it would be a reflective lookup that can only ever
 * fail. The mod platforms never take it either.
 */
public final class LunaYamlConfig {
	private static final List<String> SNAKE_YAML_PACKAGES = Collections.unmodifiableList(Arrays.asList(
		"org.yaml.snakeyaml",
		"dev.belikhun.luna.shadow.snakeyaml"
	));

	private LunaYamlConfig() {
	}

	/**
	 * Create `file` from a bundled default, or empty, when it does not exist yet.
	 *
	 * The default arrives as a supplier rather than a stream so that nothing is
	 * opened on the common path, where the file is already there.
	 */
	public static void ensureFile(Path file, Supplier<InputStream> defaultSupplier) {
		if (Files.exists(file)) {
			return;
		}

		try {
			Path parent = file.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			if (defaultSupplier != null) {
				InputStream stream = defaultSupplier.get();

				if (stream != null) {
					try {
						Files.copy(stream, file);

						return;
					} finally {
						closeQuietly(stream);
					}
				}
			}

			Files.createFile(file);
		} catch (IOException exception) {
			throw new ConfigStoreException("Không thể khởi tạo file cấu hình: " + file, exception);
		}
	}

	public static Map<String, Object> loadMap(Path file) {
		if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
			return Collections.emptyMap();
		}

		try {
			InputStream stream = Files.newInputStream(file);

			try {
				return loadMap(stream);
			} finally {
				closeQuietly(stream);
			}
		} catch (IOException exception) {
			throw new ConfigStoreException("Không thể đọc file YAML: " + file, exception);
		}
	}

	public static Map<String, Object> loadMap(InputStream stream) {
		if (stream == null) {
			return Collections.emptyMap();
		}

		Object root = invokeSnakeYamlLoad(stream);

		if (!(root instanceof Map)) {
			return Collections.emptyMap();
		}

		return normalizeMap((Map<?, ?>) root);
	}

	/**
	 * Add every key `defaults` has and `target` lacks, recursing into nested maps.
	 *
	 * @return whether anything was added, so the caller knows to rewrite the file
	 */
	public static boolean mergeMissing(Map<String, Object> target, Map<String, Object> defaults) {
		if (target == null || defaults == null || defaults.isEmpty()) {
			return false;
		}

		boolean changed = false;

		for (Map.Entry<String, Object> entry : defaults.entrySet()) {
			String key = entry.getKey();
			Object defaultValue = entry.getValue();

			if (!target.containsKey(key)) {
				target.put(key, deepCopy(defaultValue));
				changed = true;

				continue;
			}

			Object currentValue = target.get(key);

			if (currentValue instanceof Map && defaultValue instanceof Map) {
				Map<String, Object> currentMap = normalizeMap((Map<?, ?>) currentValue);
				Map<String, Object> defaultMap = normalizeMap((Map<?, ?>) defaultValue);

				if (mergeMissing(currentMap, defaultMap)) {
					target.put(key, currentMap);
					changed = true;
				}
			}
		}

		return changed;
	}

	public static void dumpMap(Path outputPath, Map<String, Object> data) {
		try {
			Class<?> dumperOptionsClass = resolveSnakeYamlClass("DumperOptions");
			Object dumperOptions = dumperOptionsClass.getConstructor().newInstance();
			Class<?> flowStyleClass = resolveSnakeYamlClass("DumperOptions$FlowStyle");
			Object blockFlowStyle = flowStyleClass.getField("BLOCK").get(null);

			dumperOptionsClass.getMethod("setDefaultFlowStyle", flowStyleClass).invoke(dumperOptions, blockFlowStyle);
			dumperOptionsClass.getMethod("setPrettyFlow", boolean.class).invoke(dumperOptions, Boolean.TRUE);
			dumperOptionsClass.getMethod("setIndent", int.class).invoke(dumperOptions, Integer.valueOf(2));
			dumperOptionsClass.getMethod("setIndicatorIndent", int.class).invoke(dumperOptions, Integer.valueOf(1));
			dumperOptionsClass.getMethod("setSplitLines", boolean.class).invoke(dumperOptions, Boolean.FALSE);

			Class<?> yamlClass = resolveSnakeYamlClass("Yaml");
			Object yaml = yamlClass.getConstructor(dumperOptionsClass).newInstance(dumperOptions);
			Path parent = outputPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);

			try {
				yamlClass.getMethod("dump", Object.class, Writer.class).invoke(yaml, data, writer);
			} finally {
				writer.close();
			}
		} catch (Exception exception) {
			throw new ConfigStoreException("Không thể ghi file YAML: " + outputPath, exception);
		}
	}

	private static Object invokeSnakeYamlLoad(InputStream stream) {
		try {
			Class<?> yamlClass = resolveSnakeYamlClass("Yaml");
			Object yaml = yamlClass.getConstructor().newInstance();
			Method loadMethod = yamlClass.getMethod("load", Reader.class);

			return loadMethod.invoke(yaml, new InputStreamReader(stream, StandardCharsets.UTF_8));
		} catch (ClassNotFoundException exception) {
			throw new ConfigStoreException(
				"Thiếu SnakeYAML trên classpath. Hãy đảm bảo nền tảng hiện tại đã cung cấp hoặc nhúng thư viện này.",
				exception
			);
		} catch (Exception exception) {
			throw new ConfigStoreException("Không thể gọi SnakeYAML để đọc cấu hình.", exception);
		}
	}

	private static Class<?> resolveSnakeYamlClass(String simpleName) throws ClassNotFoundException {
		ClassNotFoundException lastException = null;

		for (String packageName : SNAKE_YAML_PACKAGES) {
			try {
				return Class.forName(packageName + "." + simpleName);
			} catch (ClassNotFoundException exception) {
				lastException = exception;
			}
		}

		if (lastException != null) {
			throw lastException;
		}

		throw new ClassNotFoundException(simpleName);
	}

	private static Map<String, Object> normalizeMap(Map<?, ?> raw) {
		Map<String, Object> normalized = new LinkedHashMap<String, Object>();

		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			normalized.put(String.valueOf(entry.getKey()), normalizeNode(entry.getValue()));
		}

		return normalized;
	}

	private static Object normalizeNode(Object value) {
		if (value instanceof Map) {
			return normalizeMap((Map<?, ?>) value);
		}

		if (value instanceof List) {
			List<Object> copied = new ArrayList<Object>();

			for (Object item : (List<?>) value) {
				copied.add(normalizeNode(item));
			}

			return copied;
		}

		return value;
	}

	private static Object deepCopy(Object value) {
		if (value instanceof Map) {
			Map<String, Object> copied = new LinkedHashMap<String, Object>();

			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				copied.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
			}

			return copied;
		}

		if (value instanceof List) {
			List<Object> copied = new ArrayList<Object>();

			for (Object item : (List<?>) value) {
				copied.add(deepCopy(item));
			}

			return copied;
		}

		return value;
	}

	private static void closeQuietly(InputStream stream) {
		if (stream == null) {
			return;
		}

		try {
			stream.close();
		} catch (IOException ignored) {
			// the caller is already past the point where this could matter
		}
	}
}
