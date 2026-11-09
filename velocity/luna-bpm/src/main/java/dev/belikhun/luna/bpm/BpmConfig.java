package dev.belikhun.luna.bpm;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** config.yml of luna-bpm; the keys match PaperBPM's so an old config reads the same. */
public final class BpmConfig {

	public final long globalBandwidthMbps;
	public final long playerBandwidthMbps;
	public final boolean allowModelUpload;
	public final int modelUploadMaxMiB;
	public final int modelUploadChunksPerTick;
	public final boolean requireModelPermission;
	public final long handshakeDelayMs;

	private BpmConfig(Map<String, Object> root) {
		globalBandwidthMbps = number(root, "network.global-bandwidth-limit", 100).longValue();
		playerBandwidthMbps = number(root, "network.player-bandwidth-limit", 5).longValue();
		allowModelUpload = bool(root, "upload.allow-model-upload", true);
		modelUploadMaxMiB = number(root, "upload.model-upload-max-mib", 128).intValue();
		modelUploadChunksPerTick = Math.max(1, number(root, "upload.model-upload-chunks-per-tick", 4).intValue());
		requireModelPermission = bool(root, "models.require-model-permission", false);
		handshakeDelayMs = Math.max(250, number(root, "network.handshake-delay-ms", 2000).longValue());
	}

	public static BpmConfig load(Path dataDirectory, Logger logger) {
		Path file = dataDirectory.resolve("config.yml");
		try {
			Files.createDirectories(dataDirectory);
			if (!Files.exists(file)) {
				try (InputStream in = BpmConfig.class.getResourceAsStream("/config.yml")) {
					if (in != null) {
						Files.copy(in, file);
					}
				}
			}
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Object parsed = new Yaml().load(reader);
				if (parsed instanceof Map<?, ?> map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> root = (Map<String, Object>) map;
					return new BpmConfig(root);
				}
			}
		} catch (Exception e) {
			logger.warn("Could not read {}, using defaults: {}", file, e.getMessage());
		}
		return new BpmConfig(Map.of());
	}

	private static Object lookup(Map<String, Object> root, String path) {
		Object current = root;
		for (String part : path.split("\\.")) {
			if (!(current instanceof Map<?, ?> map)) {
				return null;
			}
			current = map.get(part);
		}
		return current;
	}

	private static Number number(Map<String, Object> root, String path, Number fallback) {
		Object value = lookup(root, path);
		return value instanceof Number n ? n : fallback;
	}

	private static boolean bool(Map<String, Object> root, String path, boolean fallback) {
		Object value = lookup(root, path);
		return value instanceof Boolean b ? b : fallback;
	}
}
