package dev.belikhun.luna.core.api.config;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the velocity forwarding secret a mod-loader backend was configured with.
 *
 * None of these loaders speaks modern forwarding on its own; each ecosystem
 * bolts on a mod that does, and that mod's config file is where the secret ends
 * up. So the secret is read from whichever of those files is present rather than
 * asked for a second time in luna's own config, which is what keeps a backend
 * from drifting out of sync with the proxy it is actually behind.
 */
public final class ForwardingSecretResolver {
	/**
	 * One forwarding mod's config file and the keys it holds the secret under.
	 * A blank {@code section} means the key sits at the top level of the TOML.
	 */
	public record Source(
		String fileName,
		String section,
		String secretKey,
		String secretFileKey,
		String secretTypeKey
	) {
		public static Source topLevel(String fileName, String secretKey) {
			return new Source(fileName, "", secretKey, "", "");
		}
	}

	/** Every forwarding mod luna provisions, newest ecosystem first. */
	public static final List<Source> KNOWN_SOURCES = List.of(
		new Source("neovelocity-common.toml", "forwarding", "forwarding-secret", "forwarding-secret-file", "forwarding-secret-type"),
		Source.topLevel("FabricProxy-Lite.toml", "secret"),
		Source.topLevel("pcf-common.toml", "forwardingSecret")
	);

	private ForwardingSecretResolver() {
	}

	/**
	 * Resolve the secret from the first known forwarding config present in the
	 * given config directory. Returns an empty string when none of them carries
	 * one, having said in the log which files were looked for.
	 */
	public static String resolve(Path configDir, LunaLogger logger) {
		return resolve(configDir, KNOWN_SOURCES, logger);
	}

	public static String resolve(Path configDir, List<Source> sources, LunaLogger logger) {
		Path directory = configDir.toAbsolutePath().normalize();
		List<String> looked = new ArrayList<>();

		for (Source source : sources) {
			Path configPath = directory.resolve(source.fileName());
			looked.add(source.fileName());

			if (!Files.exists(configPath) || !Files.isRegularFile(configPath)) {
				continue;
			}

			String secret = readSecret(directory, configPath, source, logger);
			if (!secret.isBlank()) {
				return secret;
			}
		}

		logger.warn(
			"Không tìm thấy forwarding secret trong thư mục config (đã tìm: "
				+ String.join(", ", looked) + "), heartbeat sẽ không hoạt động."
		);
		return "";
	}

	private static String readSecret(Path configDir, Path configPath, Source source, LunaLogger logger) {
		String inlineSecret = "";
		String secretFile = "";
		String secretType = "";
		boolean wantsSection = !source.section().isBlank();
		boolean inSection = !wantsSection;

		try {
			for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
				String trimmed = stripComment(line).trim();
				if (trimmed.isEmpty()) {
					continue;
				}

				if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
					// a top-level key stops being top-level once any table starts
					inSection = wantsSection && trimmed.substring(1, trimmed.length() - 1).trim().equalsIgnoreCase(source.section());
					continue;
				}

				if (!inSection) {
					continue;
				}

				String[] pair = trimmed.split("=", 2);
				if (pair.length != 2) {
					continue;
				}

				String key = pair[0].trim().toLowerCase(Locale.ROOT);
				String value = unquote(pair[1].trim());
				if (key.equals(source.secretKey().toLowerCase(Locale.ROOT))) {
					inlineSecret = value;
				} else if (!source.secretFileKey().isBlank() && key.equals(source.secretFileKey().toLowerCase(Locale.ROOT))) {
					secretFile = value;
				} else if (!source.secretTypeKey().isBlank() && key.equals(source.secretTypeKey().toLowerCase(Locale.ROOT))) {
					secretType = value;
				}
			}
		} catch (IOException exception) {
			logger.warn("Không thể đọc " + source.fileName() + " để resolve forwarding secret.");
			return "";
		}

		boolean useFile = "FILE".equalsIgnoreCase(secretType) || !secretFile.isBlank();
		if (useFile) {
			return resolveSecretFromFile(configDir, !secretFile.isBlank() ? secretFile : inlineSecret, logger);
		}

		return inlineSecret;
	}

	private static String resolveSecretFromFile(Path configDir, String configuredPath, LunaLogger logger) {
		if (configuredPath == null || configuredPath.isBlank()) {
			logger.warn("forwarding-secret-type=FILE nhưng thiếu đường dẫn secret file, heartbeat sẽ không hoạt động.");
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

		logger.warn("Không tìm thấy forwarding secret file: " + configuredPath);
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
