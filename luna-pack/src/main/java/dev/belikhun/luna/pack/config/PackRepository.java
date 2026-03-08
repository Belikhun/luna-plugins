package dev.belikhun.luna.pack.config;

import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PackRepository {
	private final Path packsDirectory;
	private final LunaLogger logger;

	public PackRepository(Path dataDirectory, LunaLogger logger) {
		this.packsDirectory = dataDirectory.resolve("packs");
		this.logger = logger.scope("PackRepository");
	}

	public LoadResult load() {
		ensureDirectory();
		Map<String, PackDefinition> definitions = new LinkedHashMap<>();
		List<Path> invalidFiles = new ArrayList<>();
		int discovered = 0;

		try (var stream = Files.list(packsDirectory)) {
			List<Path> files = stream
				.filter(Files::isRegularFile)
				.filter(this::isYamlFile)
				.sorted()
				.toList();

			discovered = files.size();
			for (Path file : files) {
				PackDefinition definition = loadOne(file);
				if (definition == null) {
					invalidFiles.add(file);
					continue;
				}

				String key = definition.normalizedName();
				if (definitions.containsKey(key)) {
					logger.warn("Trùng tên pack '" + definition.name() + "' tại " + file.getFileName() + ", bỏ qua.");
					invalidFiles.add(file);
					continue;
				}
				definitions.put(key, definition);
			}
		} catch (IOException exception) {
			logger.error("Không thể đọc thư mục packs: " + packsDirectory, exception);
		}

		return new LoadResult(discovered, definitions, invalidFiles);
	}

	private void ensureDirectory() {
		if (Files.exists(packsDirectory)) {
			return;
		}

		try {
			Files.createDirectories(packsDirectory);
			logger.audit("Đã tạo thư mục packs tại " + packsDirectory);
		} catch (IOException exception) {
			logger.error("Không thể tạo thư mục packs: " + packsDirectory, exception);
		}
	}

	private PackDefinition loadOne(Path file) {
		try {
			Map<String, Object> map = LunaYamlConfig.loadMap(file);
			if (map.isEmpty()) {
				logger.warn("File pack không hợp lệ (rỗng hoặc sai định dạng): " + file.getFileName());
				return null;
			}

			String name = readString(map, "name");
			String filename = readString(map, "filename");
			if (name.isBlank() || filename.isBlank()) {
				logger.warn("Thiếu name hoặc filename trong " + file.getFileName());
				return null;
			}

			if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
				logger.warn("filename không hợp lệ trong " + file.getFileName() + ": " + filename);
				return null;
			}

			int priority = readInt(map, "priority", 0);
			boolean required = readBoolean(map, "required", false);
			boolean enabled = readBoolean(map, "enabled", true);
			List<String> servers = readServers(map.get("servers"));
			if (servers.isEmpty()) {
				logger.warn("servers trống hoặc không hợp lệ trong " + file.getFileName());
				return null;
			}

			return new PackDefinition(
				name.trim(),
				filename.trim(),
				priority,
				required,
				enabled,
				servers,
				file
			);
		} catch (RuntimeException exception) {
			logger.error("Không thể phân tích file pack " + file.getFileName() + ".", exception);
			return null;
		}
	}

	private boolean isYamlFile(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".yml") || name.endsWith(".yaml");
	}

	private String readString(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? "" : String.valueOf(value).trim();
	}

	private int readInt(Map<?, ?> map, String key, int fallback) {
		Object value = map.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private boolean readBoolean(Map<?, ?> map, String key, boolean fallback) {
		Object value = map.get(key);
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value == null) {
			return fallback;
		}
		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		if (text.equals("true") || text.equals("yes") || text.equals("1")) {
			return true;
		}
		if (text.equals("false") || text.equals("no") || text.equals("0")) {
			return false;
		}
		return fallback;
	}

	private List<String> readServers(Object raw) {
		Set<String> values = new LinkedHashSet<>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				String value = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
				if (!value.isEmpty()) {
					values.add(value);
				}
			}
		} else if (raw != null) {
			String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
			if (!value.isEmpty()) {
				values.add(value);
			}
		}

		if (values.contains("all")) {
			values.remove("all");
			values.add("*");
		}
		return List.copyOf(values);
	}

	public record LoadResult(
		int discoveredFiles,
		Map<String, PackDefinition> definitions,
		List<Path> invalidFiles
	) {
	}
}
