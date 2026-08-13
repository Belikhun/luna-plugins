package dev.belikhun.luna.legacy.config;

import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds the velocity forwarding secret a mod-loader backend was configured with.
 *
 * None of these loaders speaks modern forwarding on its own; each ecosystem bolts on
 * a mod that does, and that mod's config file is where the secret ends up. So the
 * secret is read from whichever of those files is present rather than asked for a
 * second time in luna's own config, which is what keeps a backend from drifting out
 * of sync with the proxy it is actually behind.
 *
 * On 1.12.2 the mod is Proxy-Compatible-Forge, whose config file is the same
 * `proxy-compatible-forge.toml` the modern forge line already uses - so the source
 * list below needs nothing added for this era. What differs is only that PCF needs
 * MixinBooter beside it there, which is a deployment concern, not this file's.
 *
 * The parser is deliberately a line reader rather than a TOML library: it needs four
 * keys out of two possible tables, and a dependency that must then be shaded into
 * every jar is a poor trade for that.
 */
public final class ForwardingSecretResolver {
	/**
	 * One forwarding mod's config file and the keys it holds the secret under.
	 * A blank {@code section} means the key sits at the top level of the TOML.
	 */
	public static final class Source {
		private final String fileName;
		private final String section;
		private final String secretKey;
		private final String secretFileKey;
		private final String secretTypeKey;

		public Source(String fileName, String section, String secretKey, String secretFileKey, String secretTypeKey) {
			this.fileName = fileName;
			this.section = section;
			this.secretKey = secretKey;
			this.secretFileKey = secretFileKey;
			this.secretTypeKey = secretTypeKey;
		}

		public static Source topLevel(String fileName, String secretKey) {
			return new Source(fileName, "", secretKey, "", "");
		}

		public String fileName() {
			return fileName;
		}

		public String section() {
			return section;
		}

		public String secretKey() {
			return secretKey;
		}

		public String secretFileKey() {
			return secretFileKey;
		}

		public String secretTypeKey() {
			return secretTypeKey;
		}
	}

	/**
	 * Every forwarding mod luna provisions, newest ecosystem first.
	 * proxy-compatible-forge.toml is PCF 1.2+; pcf-common.toml is its retired 1.1.x
	 * line, kept for a backend still carrying the old jar.
	 */
	public static final List<Source> KNOWN_SOURCES = Collections.unmodifiableList(Arrays.asList(
		new Source("neovelocity-common.toml", "forwarding", "forwarding-secret", "forwarding-secret-file", "forwarding-secret-type"),
		Source.topLevel("FabricProxy-Lite.toml", "secret"),
		new Source("proxy-compatible-forge.toml", "forwarding", "secret", "", ""),
		Source.topLevel("pcf-common.toml", "forwardingSecret")
	));

	private ForwardingSecretResolver() {
	}

	/**
	 * Resolve the secret from the first known forwarding config present in the given
	 * config directory. Returns an empty string when none of them carries one, having
	 * said in the log which files were looked for.
	 */
	public static String resolve(Path configDir, LunaLogger logger) {
		return resolve(configDir, KNOWN_SOURCES, logger);
	}

	public static String resolve(Path configDir, List<Source> sources, LunaLogger logger) {
		Path directory = configDir.toAbsolutePath().normalize();
		List<String> looked = new ArrayList<String>();

		for (Source source : sources) {
			Path configPath = directory.resolve(source.fileName());

			looked.add(source.fileName());

			if (!Files.exists(configPath) || !Files.isRegularFile(configPath)) {
				continue;
			}

			String secret = readSecret(directory, configPath, source, logger);

			if (Strings.hasText(secret)) {
				return secret;
			}
		}

		logger.warn(
			"Không tìm thấy forwarding secret trong thư mục config (đã tìm: "
				+ join(looked) + "), heartbeat sẽ không hoạt động."
		);

		return "";
	}

	private static String readSecret(Path configDir, Path configPath, Source source, LunaLogger logger) {
		String inlineSecret = "";
		String secretFile = "";
		String secretType = "";
		boolean wantsSection = Strings.hasText(source.section());
		boolean inSection = !wantsSection;

		try {
			for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
				String trimmed = stripComment(line).trim();

				if (trimmed.isEmpty()) {
					continue;
				}

				if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
					// a top-level key stops being top-level once any table starts
					String table = trimmed.substring(1, trimmed.length() - 1).trim();

					inSection = wantsSection && table.equalsIgnoreCase(source.section());

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
				} else if (Strings.hasText(source.secretFileKey()) && key.equals(source.secretFileKey().toLowerCase(Locale.ROOT))) {
					secretFile = value;
				} else if (Strings.hasText(source.secretTypeKey()) && key.equals(source.secretTypeKey().toLowerCase(Locale.ROOT))) {
					secretType = value;
				}
			}
		} catch (IOException exception) {
			logger.warn("Không thể đọc " + source.fileName() + " để resolve forwarding secret.");

			return "";
		}

		if ("FILE".equalsIgnoreCase(secretType) || Strings.hasText(secretFile)) {
			return resolveSecretFromFile(configDir, Strings.hasText(secretFile) ? secretFile : inlineSecret, logger);
		}

		return inlineSecret;
	}

	private static String resolveSecretFromFile(Path configDir, String configuredPath, LunaLogger logger) {
		if (Strings.isBlank(configuredPath)) {
			logger.warn("forwarding-secret-type=FILE nhưng thiếu đường dẫn secret file, heartbeat sẽ không hoạt động.");

			return "";
		}

		Path relative = Paths.get(configuredPath);
		List<Path> candidates = new ArrayList<Path>();

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
				String secret = new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8).trim();

				if (Strings.hasText(secret)) {
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
		if (Strings.isBlank(value)) {
			return "";
		}

		String trimmed = value.trim();

		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}

		return trimmed;
	}

	private static String join(List<String> values) {
		StringBuilder out = new StringBuilder();

		for (String value : values) {
			if (out.length() > 0) {
				out.append(", ");
			}

			out.append(value);
		}

		return out.toString();
	}
}
