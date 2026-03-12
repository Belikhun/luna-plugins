package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class VelocityForwardingSecretResolver {
	private VelocityForwardingSecretResolver() {
	}

	public static String resolve(Path dataDirectory, LunaLogger logger) {
		Path root = resolveProxyRoot(dataDirectory);
		Path velocityToml = root.resolve("velocity.toml");
		String secretFile = "forwarding.secret";

		if (Files.exists(velocityToml)) {
			try {
				List<String> lines = Files.readAllLines(velocityToml, StandardCharsets.UTF_8);
				for (String line : lines) {
					if (line == null) {
						continue;
					}

					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("#")) {
						continue;
					}

					if (!trimmed.startsWith("forwarding-secret-file")) {
						continue;
					}

					String[] pair = trimmed.split("=", 2);
					if (pair.length != 2) {
						continue;
					}

					String configured = pair[1].trim();
					if (configured.startsWith("\"") && configured.endsWith("\"") && configured.length() >= 2) {
						configured = configured.substring(1, configured.length() - 1);
					}
					if (!configured.isBlank()) {
						secretFile = configured;
					}
				}
			} catch (IOException exception) {
				logger.warn("Không thể đọc velocity.toml để resolve forwarding-secret-file, dùng mặc định forwarding.secret");
			}
		}

		Path secretPath = root.resolve(secretFile).normalize();
		if (!Files.exists(secretPath)) {
			logger.warn("Không tìm thấy forwarding secret file tại " + secretPath);
			return "";
		}

		try {
			String secret = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
			if (secret.isBlank()) {
				logger.warn("Forwarding secret file trống: " + secretPath);
				return "";
			}
			return secret;
		} catch (IOException exception) {
			logger.warn("Không thể đọc forwarding secret file: " + secretPath);
			return "";
		}
	}

	private static Path resolveProxyRoot(Path dataDirectory) {
		if (dataDirectory == null) {
			return Path.of(".").toAbsolutePath().normalize();
		}

		Path pluginsDir = dataDirectory.getParent();
		if (pluginsDir == null) {
			return dataDirectory.toAbsolutePath().normalize();
		}

		Path root = pluginsDir.getParent();
		if (root == null) {
			return pluginsDir.toAbsolutePath().normalize();
		}

		return root.toAbsolutePath().normalize();
	}
}
