package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NeoForgeForwardingSecretResolver {
	private NeoForgeForwardingSecretResolver() {
	}

	public static String resolve(LunaLogger logger) {
		Path configDir = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize();
		Path configPath = configDir.resolve("neovelocity-common.toml");
		if (!Files.exists(configPath) || !Files.isRegularFile(configPath)) {
			logger.warn("Không tìm thấy neovelocity-common.toml trong thư mục config, heartbeat sẽ không hoạt động.");
			return "";
		}

		String inlineSecret = "";
		String secretFile = "";
		String secretType = "";
		boolean inForwardingSection = false;
		try {
			List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
			for (String line : lines) {
				String trimmed = stripComment(line).trim();
				if (trimmed.isEmpty()) {
					continue;
				}

				if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
					inForwardingSection = "[forwarding]".equalsIgnoreCase(trimmed);
					continue;
				}

				if (!inForwardingSection) {
					continue;
				}

				String[] pair = trimmed.split("=", 2);
				if (pair.length != 2) {
					continue;
				}

				String key = pair[0].trim().toLowerCase(Locale.ROOT);
				String value = unquote(pair[1].trim());
				switch (key) {
					case "forwarding-secret" -> inlineSecret = value;
					case "forwarding-secret-file" -> secretFile = value;
					case "forwarding-secret-type" -> secretType = value;
					default -> {
					}
				}
			}
		} catch (IOException exception) {
			logger.warn("Không thể đọc neovelocity-common.toml để resolve forwarding secret.");
			return "";
		}

		boolean useFile = "FILE".equalsIgnoreCase(secretType) || !secretFile.isBlank();
		if (useFile) {
			String configuredPath = !secretFile.isBlank() ? secretFile : inlineSecret;
			return resolveSecretFromFile(configDir, configuredPath, logger);
		}

		if (!inlineSecret.isBlank()) {
			return inlineSecret;
		}

		logger.warn("Không tìm thấy forwarding secret trong neovelocity-common.toml, heartbeat sẽ không hoạt động.");
		return "";
	}

	private static String resolveSecretFromFile(Path configDir, String configuredPath, LunaLogger logger) {
		if (configuredPath == null || configuredPath.isBlank()) {
			logger.warn("forwarding-secret-type=FILE nhưng thiếu forwarding-secret-file, heartbeat sẽ không hoạt động.");
			return "";
		}

		Path relative = Path.of(configuredPath);
		List<Path> candidates = new ArrayList<>();
		if (relative.isAbsolute()) {
			candidates.add(relative.normalize());
		} else {
			candidates.add(configDir.resolve(relative).normalize());
			Path root = configDir.getParent();
			if (root != null) {
				candidates.add(root.resolve(relative).normalize());
			}
		}

		for (Path candidate : candidates) {
			if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
				continue;
			}

			try {
				String secret = Files.readString(candidate, StandardCharsets.UTF_8).trim();
				if (!secret.isBlank()) {
					return secret;
				}
				logger.warn("Forwarding secret file trống: " + candidate);
				return "";
			} catch (IOException exception) {
				logger.warn("Không thể đọc forwarding secret file: " + candidate);
				return "";
			}
		}

		logger.warn("Không tìm thấy forwarding secret file cho NeoVelocity: " + configuredPath);
		return "";
	}

	private static String stripComment(String line) {
		if (line == null) {
			return "";
		}

		int commentIndex = line.indexOf('#');
		return commentIndex >= 0 ? line.substring(0, commentIndex) : line;
	}

	private static String unquote(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}

		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}
}
