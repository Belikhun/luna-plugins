package dev.belikhun.luna.core.api.config;

import dev.belikhun.luna.core.api.exception.ConfigStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class LunaYamlConfig {
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

	private static Map<String, Object> loadMapWithSnakeYaml(Path file) {
		try (InputStream stream = Files.newInputStream(file)) {
			Object root = invokeSnakeYamlLoad(stream);
			if (!(root instanceof Map<?, ?> map)) {
				return Map.of();
			}

			Map<String, Object> normalized = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				normalized.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return normalized;
		} catch (IOException | RuntimeException exception) {
			throw new ConfigStoreException("Không thể đọc file YAML: " + file, exception);
		}
	}

	private static Object invokeSnakeYamlLoad(InputStream stream) {
		try {
			Class<?> yamlClass = Class.forName("org.yaml.snakeyaml.Yaml");
			Object yaml = yamlClass.getConstructor().newInstance();
			Method loadMethod = yamlClass.getMethod("load", java.io.Reader.class);
			return loadMethod.invoke(yaml, new InputStreamReader(stream, StandardCharsets.UTF_8));
		} catch (ClassNotFoundException exception) {
			throw new ConfigStoreException("Thiếu SnakeYAML trên classpath. Hãy đảm bảo plugin nền tảng (luna-core-velocity) cung cấp thư viện này.", exception);
		} catch (Exception exception) {
			throw new ConfigStoreException("Không thể gọi SnakeYAML để đọc cấu hình.", exception);
		}
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

	private static boolean isBukkitYamlAvailable() {
		try {
			Class.forName("org.bukkit.configuration.file.YamlConfiguration");
			return true;
		} catch (ClassNotFoundException ignored) {
			return false;
		}
	}
}
