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
	private Path packsDirectory;
	private final LunaLogger logger;

	public PackRepository(Path dataDirectory, LunaLogger logger) {
		this.packsDirectory = dataDirectory.resolve("packs");
		this.logger = logger.scope("PackRepository");
	}

	public void setPacksDirectory(Path packsDirectory) {
		if (packsDirectory == null) {
			return;
		}
		this.packsDirectory = packsDirectory.normalize();
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

	public TemplateCreateResult createTemplate(String packNameRaw) {
		ensureDirectory();
		String normalized = normalizePackName(packNameRaw);
		if (normalized == null) {
			return TemplateCreateResult.invalidName();
		}

		LoadResult current = load();
		if (current.definitions().containsKey(normalized)) {
			return TemplateCreateResult.alreadyExists();
		}

		Path file = packsDirectory.resolve(normalized + ".yml");
		if (Files.exists(file)) {
			return TemplateCreateResult.alreadyExists();
		}

		String content = "name: \"" + normalized + "\"\n"
			+ "filename: \"" + normalized + ".zip\"\n"
			+ "priority: 0\n"
			+ "required: false\n"
			+ "enabled: true\n"
			+ "servers:\n"
			+ "  - \"*\"\n";

		try {
			Files.writeString(file, content);
			logger.audit("Đã tạo template pack mới: " + file.getFileName());
			return TemplateCreateResult.created(file);
		} catch (IOException exception) {
			logger.error("Không thể tạo template pack: " + file.getFileName(), exception);
			return TemplateCreateResult.ioError();
		}
	}

	public ToggleResult setEnabled(String packName, boolean enabled) {
		String normalized = normalizePackName(packName);
		if (normalized == null) {
			return ToggleResult.invalidName();
		}

		LoadResult current = load();
		PackDefinition definition = current.definitions().get(normalized);
		if (definition == null) {
			return ToggleResult.notFound();
		}

		if (definition.enabled() == enabled) {
			return ToggleResult.noChange(definition.enabled());
		}

		String content = "name: \"" + definition.name() + "\"\n"
			+ "filename: \"" + definition.filename() + "\"\n"
			+ "priority: " + definition.priority() + "\n"
			+ "required: " + definition.required() + "\n"
			+ "enabled: " + enabled + "\n"
			+ "servers:\n";
		for (String server : definition.servers()) {
			content += "  - \"" + server + "\"\n";
		}

		try {
			Files.writeString(definition.sourceFile(), content);
			logger.audit("Đã cập nhật enabled=" + enabled + " cho pack " + definition.name());
			return ToggleResult.updated(definition.enabled(), enabled);
		} catch (IOException exception) {
			logger.error("Không thể cập nhật pack " + definition.sourceFile().getFileName(), exception);
			return ToggleResult.ioError();
		}
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

	private String normalizePackName(String value) {
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

	public static final class TemplateCreateResult {
		private final boolean created;
		private final boolean alreadyExists;
		private final boolean invalidName;
		private final boolean ioError;
		private final Path path;

		private TemplateCreateResult(boolean created, boolean alreadyExists, boolean invalidName, boolean ioError, Path path) {
			this.created = created;
			this.alreadyExists = alreadyExists;
			this.invalidName = invalidName;
			this.ioError = ioError;
			this.path = path;
		}

		public static TemplateCreateResult created(Path path) {
			return new TemplateCreateResult(true, false, false, false, path);
		}

		public static TemplateCreateResult alreadyExists() {
			return new TemplateCreateResult(false, true, false, false, null);
		}

		public static TemplateCreateResult invalidName() {
			return new TemplateCreateResult(false, false, true, false, null);
		}

		public static TemplateCreateResult ioError() {
			return new TemplateCreateResult(false, false, false, true, null);
		}

		public boolean isCreated() {
			return created;
		}

		public boolean isAlreadyExists() {
			return alreadyExists;
		}

		public boolean isInvalidName() {
			return invalidName;
		}

		public boolean isIoError() {
			return ioError;
		}

		public Path path() {
			return path;
		}
	}

	public static final class ToggleResult {
		private final boolean updated;
		private final boolean notFound;
		private final boolean invalidName;
		private final boolean ioError;
		private final boolean oldEnabled;
		private final boolean newEnabled;

		private ToggleResult(boolean updated, boolean notFound, boolean invalidName, boolean ioError, boolean oldEnabled, boolean newEnabled) {
			this.updated = updated;
			this.notFound = notFound;
			this.invalidName = invalidName;
			this.ioError = ioError;
			this.oldEnabled = oldEnabled;
			this.newEnabled = newEnabled;
		}

		public static ToggleResult updated(boolean oldEnabled, boolean newEnabled) {
			return new ToggleResult(true, false, false, false, oldEnabled, newEnabled);
		}

		public static ToggleResult notFound() {
			return new ToggleResult(false, true, false, false, false, false);
		}

		public static ToggleResult invalidName() {
			return new ToggleResult(false, false, true, false, false, false);
		}

		public static ToggleResult ioError() {
			return new ToggleResult(false, false, false, true, false, false);
		}

		public static ToggleResult noChange(boolean value) {
			return new ToggleResult(true, false, false, false, value, value);
		}

		public boolean isUpdated() {
			return updated;
		}

		public boolean isNotFound() {
			return notFound;
		}

		public boolean isInvalidName() {
			return invalidName;
		}

		public boolean isIoError() {
			return ioError;
		}

		public boolean oldEnabled() {
			return oldEnabled;
		}

		public boolean newEnabled() {
			return newEnabled;
		}
	}
}
