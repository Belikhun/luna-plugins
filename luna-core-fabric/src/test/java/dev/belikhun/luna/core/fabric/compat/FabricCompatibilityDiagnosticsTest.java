package dev.belikhun.luna.core.fabric.compat;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricCompatibilityDiagnosticsTest {

	@Test
	void reportsAvailableWhenAnyCandidateIsPresent() {
		Set<String> available = Set.of(
			"me.devnatan.fabricproxy_lite.api.FabricProxyLiteAPI",
			"net.luckperms.api.LuckPerms"
		);

		FabricCompatibilityDiagnostics.CompatibilitySnapshot snapshot = FabricCompatibilityDiagnostics.scan(available::contains);
		assertTrue(snapshot.fabricProxyLite());
		assertTrue(snapshot.luckPerms());
		assertFalse(snapshot.spark());
		assertFalse(snapshot.skinsRestorer());
		assertFalse(snapshot.voicechat());
	}

	@Test
	void reportsMissingWhenNoCandidatesArePresent() {
		FabricCompatibilityDiagnostics.CompatibilitySnapshot snapshot = FabricCompatibilityDiagnostics.scan(name -> false);
		assertFalse(snapshot.fabricProxyLite());
		assertFalse(snapshot.textPlaceholderApi());
		assertFalse(snapshot.luckPerms());
		assertFalse(snapshot.spark());
		assertFalse(snapshot.skinsRestorer());
		assertFalse(snapshot.voicechat());
	}
}
