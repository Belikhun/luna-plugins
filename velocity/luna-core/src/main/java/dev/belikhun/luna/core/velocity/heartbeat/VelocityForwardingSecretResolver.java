package dev.belikhun.luna.core.velocity.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

		List<Path> candidates = resolveSecretCandidates(root, secretFile);
		Path secretPath = null;
		for (Path candidate : candidates) {
			if (Files.exists(candidate)) {
				secretPath = candidate;
				break;
			}
		}

		if (secretPath == null) {
			String searched = candidates.stream()
				.map(Path::toString)
				.distinct()
				.reduce((first, second) -> first + ", " + second)
				.orElse(root.resolve(secretFile).normalize().toString());
			logger.warn("Không tìm thấy forwarding secret file. Đã tìm tại: " + searched);
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

		Path absoluteDataDirectory = dataDirectory.toAbsolutePath().normalize();
		Path discoveredFromToml = discoverProxyRootFromVelocityToml(absoluteDataDirectory);
		if (discoveredFromToml != null) {
			return discoveredFromToml;
		}

		Path fileName = absoluteDataDirectory.getFileName();
		if (fileName != null && "plugins".equalsIgnoreCase(fileName.toString())) {
			Path root = absoluteDataDirectory.getParent();
			return root == null ? absoluteDataDirectory : root;
		}

		Path parent = absoluteDataDirectory.getParent();
		if (parent != null) {
			Path parentName = parent.getFileName();
			if (parentName != null && "plugins".equalsIgnoreCase(parentName.toString())) {
				Path root = parent.getParent();
				return root == null ? parent : root;
			}
		}

		Path pluginsDir = absoluteDataDirectory.getParent();
		if (pluginsDir == null) {
			return absoluteDataDirectory;
		}

		Path root = pluginsDir.getParent();
		if (root == null) {
			return pluginsDir.toAbsolutePath().normalize();
		}

		return root.toAbsolutePath().normalize();
	}

	private static Path discoverProxyRootFromVelocityToml(Path start) {
		Path current = start;
		for (int i = 0; i < 8 && current != null; i++) {
			if (Files.exists(current.resolve("velocity.toml"))) {
				return current;
			}
			current = current.getParent();
		}
		return null;
	}

	private static List<Path> resolveSecretCandidates(Path root, String secretFile) {
		List<Path> candidates = new ArrayList<>();
		Path configured = Path.of(secretFile);
		if (configured.isAbsolute()) {
			candidates.add(configured.normalize());
			return candidates;
		}

		candidates.add(root.resolve(configured).normalize());
		return candidates;
	}
}
