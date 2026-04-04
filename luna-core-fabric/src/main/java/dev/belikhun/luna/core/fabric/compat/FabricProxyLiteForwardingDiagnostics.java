package dev.belikhun.luna.core.fabric.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

final class FabricProxyLiteForwardingDiagnostics {
	private static final String ENV_SECRET = "FABRIC_PROXY_SECRET";
	private static final String ENV_SECRET_FILE = "FABRIC_PROXY_SECRET_FILE";
	private static final String CONFIG_FILE_NAME = "FabricProxy-Lite.toml";

	private FabricProxyLiteForwardingDiagnostics() {
	}

	static Snapshot inspect(Path serverRoot, Function<String, String> envLookup) {
		Path root = serverRoot == null ? Path.of(".").toAbsolutePath().normalize() : serverRoot.toAbsolutePath().normalize();
		Function<String, String> env = envLookup == null ? key -> null : envLookup;

		String directSecret = normalize(env.apply(ENV_SECRET));
		if (!directSecret.isBlank()) {
			return new Snapshot(true, true, true, false, false, "env:" + ENV_SECRET, "");
		}

		String secretFileValue = normalize(env.apply(ENV_SECRET_FILE));
		if (!secretFileValue.isBlank()) {
			Path secretFile = resolveSecretFile(root, secretFileValue);
			String resolved = readSecret(secretFile);
			if (!resolved.isBlank()) {
				return new Snapshot(true, true, true, false, false, secretFile.toString(), "");
			}
		}

		for (Path configPath : resolveConfigCandidates(root)) {
			if (!Files.exists(configPath)) {
				continue;
			}

			TomlSnapshot snapshot = readTomlSnapshot(configPath);
			if (!snapshot.secret().isBlank()) {
				return new Snapshot(
					true,
					true,
					false,
					snapshot.hackEarlySend(),
					snapshot.hackMessageChain(),
					configPath.toString(),
					configPath.toString()
				);
			}

			return new Snapshot(true, false, false, snapshot.hackEarlySend(), snapshot.hackMessageChain(), "", configPath.toString());
		}

		return new Snapshot(false, false, false, false, false, "", "");
	}

	private static List<Path> resolveConfigCandidates(Path root) {
		return List.of(
			root.resolve("config").resolve(CONFIG_FILE_NAME).normalize(),
			root.resolve(CONFIG_FILE_NAME).normalize()
		);
	}

	private static Path resolveSecretFile(Path root, String configured) {
		Path path = Path.of(configured);
		if (path.isAbsolute()) {
			return path.normalize();
		}
		return root.resolve(path).normalize();
	}

	private static String readSecret(Path path) {
		if (path == null || !Files.exists(path)) {
			return "";
		}

		try {
			return normalize(Files.readString(path, StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			return "";
		}
	}

	private static TomlSnapshot readTomlSnapshot(Path configPath) {
		String secret = "";
		boolean hackEarlySend = false;
		boolean hackMessageChain = false;

		try {
			for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
				if (line == null) {
					continue;
				}

				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}

				String value = readTomlValue(trimmed, "secret");
				if (!value.isBlank()) {
					secret = value;
				}

				hackEarlySend = hackEarlySend || Boolean.parseBoolean(readTomlValue(trimmed, "hackEarlySend"));
				hackMessageChain = hackMessageChain || Boolean.parseBoolean(readTomlValue(trimmed, "hackMessageChain"));
			}
		} catch (IOException ignored) {
			return new TomlSnapshot("", false, false);
		}

		return new TomlSnapshot(secret, hackEarlySend, hackMessageChain);
	}

	private static String readTomlValue(String line, String key) {
		if (!line.startsWith(key)) {
			return "";
		}

		String[] pair = line.split("=", 2);
		if (pair.length != 2) {
			return "";
		}

		String value = normalize(pair[1]);
		if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
			value = value.substring(1, value.length() - 1);
		}
		return value.trim();
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	record Snapshot(
		boolean configPresent,
		boolean secretConfigured,
		boolean secretFromEnvironment,
		boolean hackEarlySend,
		boolean hackMessageChain,
		String secretSource,
		String configPath
	) {
	}

	private record TomlSnapshot(String secret, boolean hackEarlySend, boolean hackMessageChain) {
	}
}
