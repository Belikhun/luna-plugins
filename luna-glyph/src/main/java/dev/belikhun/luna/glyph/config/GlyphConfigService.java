package dev.belikhun.luna.glyph.config;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.glyph.model.GlyphDefinition;
import dev.belikhun.luna.glyph.model.GlyphType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GlyphConfigService {
	private static final String DEFAULT_NAME = "lunaglyph";
	private static final String DEFAULT_DESCRIPTION = "Luna Glyph Resource Pack";
	private static final String DEFAULT_FILENAME = "lunaglyph.zip";
	private static final String DEFAULT_NAMESPACE = "lunaglyph";
	private static final int DEFAULT_START_CODEPOINT = 0xE000;

	private final Path dataDirectory;
	private final LunaLogger logger;

	public GlyphConfigService(Path dataDirectory, LunaLogger logger) {
		this.dataDirectory = dataDirectory;
		this.logger = logger.scope("Config");
	}

	public void ensureDefaults() {
		copyIfMissing("config.yml");
		copyIfMissing("glyphs.yml");
		ensureDirectory(dataDirectory.resolve("glyphs"));
	}

	public GlyphPluginState loadState() {
		GlyphPackConfig packConfig = loadPackConfig();
		Map<String, GlyphDefinition> glyphs = loadGlyphs(packConfig.startCodepoint());
		Map<String, String> placeholders = new LinkedHashMap<>();
		for (GlyphDefinition glyph : glyphs.values()) {
			placeholders.put(glyph.name(), glyph.character());
		}

		return new GlyphPluginState(
			packConfig,
			Collections.unmodifiableMap(glyphs),
			Collections.unmodifiableMap(placeholders)
		);
	}

	private GlyphPackConfig loadPackConfig() {
		Path path = dataDirectory.resolve("config.yml");
		if (!Files.exists(path)) {
			return fallbackPackConfig();
		}

		try {
			Map<String, Object> root = LunaYamlConfig.loadMap(path);
			Map<String, Object> pack = ConfigValues.map(root, "pack");

			String name = normalizeName(ConfigValues.string(pack, "name", DEFAULT_NAME));
			String description = ConfigValues.stringPreserveWhitespace(pack.get("description"), DEFAULT_DESCRIPTION);
			String filename = normalizeFilename(ConfigValues.string(pack, "filename", DEFAULT_FILENAME));
			String namespace = normalizeNamespace(ConfigValues.string(pack, "namespace", DEFAULT_NAMESPACE));
			int priority = ConfigValues.intValue(pack, "priority", 100);
			boolean required = ConfigValues.booleanValue(pack, "required", false);
			boolean enabled = ConfigValues.booleanValue(pack, "enabled", true);
			int startCodepoint = parseCodepoint(pack.get("start-codepoint"), DEFAULT_START_CODEPOINT);
			List<String> servers = normalizeServers(pack.get("servers"));

			return new GlyphPackConfig(name, description, filename, namespace, priority, required, enabled, startCodepoint, servers);
		} catch (RuntimeException exception) {
			logger.error("Không thể đọc config.yml, dùng cấu hình mặc định.", exception);
			return fallbackPackConfig();
		}
	}

	private Map<String, GlyphDefinition> loadGlyphs(int startCodepoint) {
		Path path = dataDirectory.resolve("glyphs.yml");
		if (!Files.exists(path)) {
			return Map.of();
		}

		Map<String, GlyphDefinition> output = new LinkedHashMap<>();
		try {
			Map<String, Object> root = LunaYamlConfig.loadMap(path);
			Map<String, Object> glyphSection = ConfigValues.map(root, "glyphs");

			int codepoint = startCodepoint;
			for (Map.Entry<String, Object> entry : glyphSection.entrySet()) {
				String name = normalizeName(entry.getKey());
				if (name == null) {
					logger.warn("Bỏ qua glyph có tên không hợp lệ: " + entry.getKey());
					continue;
				}

				Map<String, Object> node = ConfigValues.map(entry.getValue());
				String fileName = normalizeFileName(ConfigValues.string(node, "file", ""), name);
				if (fileName.isBlank() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
					logger.warn("Bỏ qua glyph '" + name + "': file không hợp lệ.");
					continue;
				}

				GlyphType type = GlyphType.fromString(ConfigValues.string(node, "type", "icon"));
				Integer width = parseOptionalDimension(node.get("width"));
				Integer height = parseOptionalDimension(node.get("height"));
				if (codepoint > Character.MAX_CODE_POINT) {
					logger.warn("Hết codepoint hợp lệ khi nạp glyph, bỏ qua phần còn lại.");
					break;
				}
				if (Character.isSurrogate((char) codepoint)) {
					codepoint++;
				}

				String character = new String(Character.toChars(codepoint));
				codepoint++;
				output.put(name, new GlyphDefinition(name, fileName, type, width, height, character));
			}
		} catch (RuntimeException exception) {
			logger.error("Không thể đọc glyphs.yml.", exception);
			return Map.of();
		}

		return output;
	}

	private String normalizeFileName(String raw, String glyphName) {
		if (raw == null || raw.isBlank()) {
			return glyphName + ".png";
		}

		return raw.trim();
	}

	private GlyphPackConfig fallbackPackConfig() {
		return new GlyphPackConfig(
			DEFAULT_NAME,
			DEFAULT_DESCRIPTION,
			DEFAULT_FILENAME,
			DEFAULT_NAMESPACE,
			100,
			false,
			true,
			DEFAULT_START_CODEPOINT,
			List.of("*")
		);
	}

	private Integer parseOptionalDimension(Object raw) {
		Integer value = ConfigValues.integerValue(raw, null);
		if (value == null) {
			return null;
		}
		if (value < 1) {
			return 1;
		}
		return Math.min(256, value);
	}

	private int parseCodepoint(Object raw, int fallback) {
		if (raw == null) {
			return fallback;
		}

		if (raw instanceof Number number) {
			return normalizeCodepoint(number.intValue(), fallback);
		}

		String text = String.valueOf(raw).trim();
		if (text.isBlank()) {
			return fallback;
		}

		try {
			if (text.startsWith("0x") || text.startsWith("0X")) {
				return normalizeCodepoint(Integer.parseInt(text.substring(2), 16), fallback);
			}
			return normalizeCodepoint(Integer.parseInt(text), fallback);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private int normalizeCodepoint(int value, int fallback) {
		if (value < 0 || value > Character.MAX_CODE_POINT) {
			return fallback;
		}
		if (value >= 0xD800 && value <= 0xDFFF) {
			return fallback;
		}
		return value;
	}

	private List<String> normalizeServers(Object raw) {
		Set<String> output = new LinkedHashSet<>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				String value = normalizeServer(String.valueOf(item));
				if (value != null) {
					output.add(value);
				}
			}
		} else if (raw != null) {
			String value = normalizeServer(String.valueOf(raw));
			if (value != null) {
				output.add(value);
			}
		}

		if (output.isEmpty()) {
			return List.of("*");
		}
		return List.copyOf(output);
	}

	private String normalizeServer(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return null;
		}
		if (normalized.equals("all")) {
			return "*";
		}
		return normalized;
	}

	private String normalizeName(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return null;
		}
		if (!normalized.matches("[a-z0-9_-]{1,64}")) {
			return null;
		}
		return normalized;
	}

	private String normalizeNamespace(String value) {
		String normalized = normalizeName(value);
		return normalized == null ? DEFAULT_NAMESPACE : normalized;
	}

	private String normalizeFilename(String value) {
		if (value == null) {
			return DEFAULT_FILENAME;
		}

		String normalized = value.trim();
		if (normalized.isBlank()) {
			return DEFAULT_FILENAME;
		}
		if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
			return DEFAULT_FILENAME;
		}
		if (!normalized.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			return DEFAULT_FILENAME;
		}
		return normalized;
	}

	private void copyIfMissing(String resourceName) {
		Path target = dataDirectory.resolve(resourceName);
		if (Files.exists(target)) {
			return;
		}

		try {
			LunaYamlConfig.ensureFile(target, () -> getClass().getClassLoader().getResourceAsStream(resourceName));
			logger.info("Đã tạo tệp mặc định " + resourceName + ".");
		} catch (RuntimeException exception) {
			logger.error("Không thể tạo tệp mặc định " + resourceName + ".", exception);
		}
	}

	private void ensureDirectory(Path directory) {
		if (Files.exists(directory)) {
			return;
		}

		try {
			Files.createDirectories(directory);
		} catch (IOException exception) {
			logger.error("Không thể tạo thư mục " + directory + ".", exception);
		}
	}
}
