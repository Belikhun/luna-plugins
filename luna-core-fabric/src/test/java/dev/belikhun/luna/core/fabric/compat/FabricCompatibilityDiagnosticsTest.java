package dev.belikhun.luna.core.fabric.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricCompatibilityDiagnosticsTest {

	@Test
	void reportsAvailableWhenAnyCandidateIsPresent() {
		Set<String> available = Set.of(
			"me.devnatan.fabricproxy_lite.api.FabricProxyLiteAPI",
			"net.luckperms.api.LuckPerms"
		);

		FabricCompatibilityDiagnostics.CompatibilitySnapshot snapshot = FabricCompatibilityDiagnostics.scan(available::contains, Path.of("."), key -> null);
		assertTrue(snapshot.fabricProxyLite());
		assertTrue(snapshot.luckPerms());
		assertFalse(snapshot.fabricProxyLiteSecretConfigured());
		assertFalse(snapshot.spark());
		assertFalse(snapshot.skinsRestorer());
		assertFalse(snapshot.voicechat());
	}

	@Test
	void reportsMissingWhenNoCandidatesArePresent() {
		FabricCompatibilityDiagnostics.CompatibilitySnapshot snapshot = FabricCompatibilityDiagnostics.scan(name -> false, Path.of("."), key -> null);
		assertFalse(snapshot.fabricProxyLite());
		assertFalse(snapshot.textPlaceholderApi());
		assertFalse(snapshot.luckPerms());
		assertFalse(snapshot.spark());
		assertFalse(snapshot.skinsRestorer());
		assertFalse(snapshot.voicechat());
	}

	@Test
	void readsFabricProxyLiteSecretFromConfig(@TempDir Path tempDir) throws IOException {
		Path configDir = Files.createDirectories(tempDir.resolve("config"));
		Files.writeString(
			configDir.resolve("FabricProxy-Lite.toml"),
			"secret = \"abc123\"\n"
				+ "hackEarlySend = true\n"
				+ "hackMessageChain = true\n"
		);

		FabricCompatibilityDiagnostics.CompatibilitySnapshot snapshot = FabricCompatibilityDiagnostics.scan(
			name -> name.equals("me.devnatan.fabricproxy_lite.api.FabricProxyLiteAPI"),
			tempDir,
			key -> null
		);

		assertTrue(snapshot.fabricProxyLite());
		assertTrue(snapshot.fabricProxyLiteSecretConfigured());
		assertTrue(snapshot.fabricProxyLiteHackEarlySend());
		assertTrue(snapshot.fabricProxyLiteHackMessageChain());
		assertEquals(configDir.resolve("FabricProxy-Lite.toml").toString(), snapshot.fabricProxyLiteSecretSource());
	}

	@Test
	void prefersFabricProxyLiteSecretFromEnvironment(@TempDir Path tempDir) {
		FabricCompatibilityDiagnostics.CompatibilitySnapshot snapshot = FabricCompatibilityDiagnostics.scan(
			name -> name.equals("me.devnatan.fabricproxy_lite.api.FabricProxyLiteAPI"),
			tempDir,
			key -> "FABRIC_PROXY_SECRET".equals(key) ? "env-secret" : null
		);

		assertTrue(snapshot.fabricProxyLite());
		assertTrue(snapshot.fabricProxyLiteSecretConfigured());
		assertEquals("env:FABRIC_PROXY_SECRET", snapshot.fabricProxyLiteSecretSource());
	}
}
