package dev.belikhun.luna.pack.config;

import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class LoaderConfigService {
	private static final String DEFAULT_BASE_URL = "https://mc.belikhun.dev/mcds/pack/";
	private static final String DEFAULT_PACK_PATH = "plugins/LunaPackLoader/files";

	private final Path dataDirectory;
	private final LunaLogger logger;
	private LoaderConfig current;

	public LoaderConfigService(Path dataDirectory, LunaLogger logger) {
		this.dataDirectory = dataDirectory;
		this.logger = logger.scope("Config");
		this.current = new LoaderConfig(DEFAULT_BASE_URL, resolvePackPath(DEFAULT_PACK_PATH));
	}

	public void ensureDefaults() {
		copyIfMissing("config.yml");
	}

	public LoaderConfig load() {
		Path configPath = dataDirectory.resolve("config.yml");
		if (!Files.exists(configPath)) {
			logger.warn("Không tìm thấy config.yml, sử dụng cấu hình mặc định.");
			current = new LoaderConfig(DEFAULT_BASE_URL, resolvePackPath(DEFAULT_PACK_PATH));
			return current;
		}

		try {
			Map<String, Object> map = LunaYamlConfig.loadMap(configPath);
			String baseUrl = normalizeBaseUrl(readString(map, "base-url", DEFAULT_BASE_URL));
			String packPathRaw = readString(map, "pack-path", DEFAULT_PACK_PATH);
			current = new LoaderConfig(baseUrl, resolvePackPath(packPathRaw));
			return current;
		} catch (RuntimeException exception) {
			logger.error("Không thể đọc config.yml, sử dụng cấu hình mặc định.", exception);
			current = new LoaderConfig(DEFAULT_BASE_URL, resolvePackPath(DEFAULT_PACK_PATH));
			return current;
		}
	}

	public LoaderConfig current() {
		return current;
	}

	private String readString(Map<?, ?> map, String key, String fallback) {
		Object value = map.get(key);
		if (value == null) {
			return fallback;
		}

		String text = String.valueOf(value).trim();
		return text.isEmpty() ? fallback : text;
	}

	private String normalizeBaseUrl(String raw) {
		String candidate = raw == null ? "" : raw.trim();
		if (candidate.isBlank()) {
			return DEFAULT_BASE_URL;
		}

		try {
			URI uri = new URI(candidate);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
			if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank()) {
				throw new URISyntaxException(candidate, "Unsupported scheme or missing host");
			}

			String normalized = candidate;
			while (normalized.endsWith("/")) {
				normalized = normalized.substring(0, normalized.length() - 1);
			}
			return normalized + "/";
		} catch (URISyntaxException exception) {
			logger.warn("base-url '" + candidate + "' không hợp lệ, dùng mặc định " + DEFAULT_BASE_URL + ".");
			return DEFAULT_BASE_URL;
		}
	}

	private Path resolvePackPath(String rawPath) {
		String candidate = rawPath == null ? "" : rawPath.trim();
		if (candidate.isBlank()) {
			candidate = DEFAULT_PACK_PATH;
		}

		try {
			Path path = Paths.get(candidate);
			if (path.isAbsolute()) {
				return path.normalize();
			}

			return Paths.get("").toAbsolutePath().resolve(path).normalize();
		} catch (InvalidPathException exception) {
			logger.warn("pack-path '" + candidate + "' không hợp lệ, dùng mặc định " + DEFAULT_PACK_PATH + ".");
			return Paths.get("").toAbsolutePath().resolve(DEFAULT_PACK_PATH).normalize();
		}
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
}
