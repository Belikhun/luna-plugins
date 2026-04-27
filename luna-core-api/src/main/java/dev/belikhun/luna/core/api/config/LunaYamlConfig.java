package dev.belikhun.luna.core.api.config;

import dev.belikhun.luna.core.api.exception.ConfigStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class LunaYamlConfig {
	private static final List<String> SNAKE_YAML_PACKAGES = List.of(
		"org.yaml.snakeyaml",
		"dev.belikhun.luna.shadow.snakeyaml"
	);

	private LunaYamlConfig() {
	}

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
				try (InputStream stream = defaultSupplier.get()) {
					if (stream != null) {
						Files.copy(stream, file);
						return;
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
			return Map.of();
		}

		if (isBukkitYamlAvailable()) {
			Map<String, Object> loaded = loadMapWithBukkit(file);
			if (loaded != null) {
				return loaded;
			}
		}

		return loadMapWithSnakeYaml(file);
	}

	public static Map<String, Object> loadMap(InputStream stream) {
		if (stream == null) {
			return Map.of();
		}

		return loadMapWithSnakeYaml(stream);
	}

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
			if (currentValue instanceof Map<?, ?> currentMapRaw && defaultValue instanceof Map<?, ?> defaultMapRaw) {
				Map<String, Object> currentMap = normalizeMap(currentMapRaw);
				Map<String, Object> defaultMap = normalizeMap(defaultMapRaw);
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
			dumperOptionsClass.getMethod("setPrettyFlow", boolean.class).invoke(dumperOptions, true);
			dumperOptionsClass.getMethod("setIndent", int.class).invoke(dumperOptions, 2);
			dumperOptionsClass.getMethod("setIndicatorIndent", int.class).invoke(dumperOptions, 1);
			dumperOptionsClass.getMethod("setSplitLines", boolean.class).invoke(dumperOptions, false);

			Class<?> yamlClass = resolveSnakeYamlClass("Yaml");
			Object yaml = yamlClass.getConstructor(dumperOptionsClass).newInstance(dumperOptions);
			Path parent = outputPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
				yamlClass.getMethod("dump", Object.class, Writer.class).invoke(yaml, data, writer);
			}
		} catch (Exception exception) {
			throw new ConfigStoreException("Không thể ghi file YAML: " + outputPath, exception);
		}
	}

	private static Map<String, Object> loadMapWithSnakeYaml(Path file) {
		try (InputStream stream = Files.newInputStream(file)) {
			return loadMapWithSnakeYaml(stream);
		} catch (IOException | RuntimeException exception) {
			throw new ConfigStoreException("Không thể đọc file YAML: " + file, exception);
		}
	}

	private static Map<String, Object> loadMapWithSnakeYaml(InputStream stream) {
		Object root = invokeSnakeYamlLoad(stream);
		if (!(root instanceof Map<?, ?> map)) {
			return Map.of();
		}

		return normalizeMap(map);
	}

	private static Object invokeSnakeYamlLoad(InputStream stream) {
		try {
			Class<?> yamlClass = resolveSnakeYamlClass("Yaml");
			Object yaml = yamlClass.getConstructor().newInstance();
			Method loadMethod = yamlClass.getMethod("load", java.io.Reader.class);
			return loadMethod.invoke(yaml, new InputStreamReader(stream, StandardCharsets.UTF_8));
		} catch (ClassNotFoundException exception) {
			throw new ConfigStoreException("Thiếu SnakeYAML trên classpath. Hãy đảm bảo nền tảng hiện tại đã cung cấp hoặc nhúng thư viện này.", exception);
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

	private static Map<String, Object> loadMapWithBukkit(Path file) {
		try {
			Class<?> yamlClass = Class.forName("org.bukkit.configuration.file.YamlConfiguration");
			Method loadConfiguration = yamlClass.getMethod("loadConfiguration", java.io.File.class);
			Object configuration = loadConfiguration.invoke(null, file.toFile());
			Method getValues = yamlClass.getMethod("getValues", boolean.class);
			Object values = getValues.invoke(configuration, false);
			if (!(values instanceof Map<?, ?> map)) {
				return Map.of();
			}

			Map<String, Object> normalized = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				normalized.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return normalized;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static Map<String, Object> normalizeMap(Map<?, ?> raw) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			normalized.put(String.valueOf(entry.getKey()), normalizeNode(entry.getValue()));
		}
		return normalized;
	}

	private static Object normalizeNode(Object value) {
		if (value instanceof Map<?, ?> map) {
			return normalizeMap(map);
		}
		if (value instanceof List<?> list) {
			List<Object> copied = new ArrayList<>();
			for (Object item : list) {
				copied.add(normalizeNode(item));
			}
			return copied;
		}
		return value;
	}

	private static Object deepCopy(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copied = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				copied.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
			}
			return copied;
		}
		if (value instanceof List<?> list) {
			List<Object> copied = new ArrayList<>();
			for (Object item : list) {
				copied.add(deepCopy(item));
			}
			return copied;
		}
		return value;
	}

	private static boolean isBukkitYamlAvailable() {
		try {
			Class.forName("org.bukkit.configuration.file.YamlConfiguration");
			return true;
		} catch (ClassNotFoundException ignored) {
			return false;
		}
	}
}
