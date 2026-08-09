package dev.belikhun.luna.core.api.config;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The resolver reads whichever forwarding mod's config the backend happens to
 * have, so each supported shape is pinned here: getting one wrong means a
 * backend that starts fine and never appears in the console.
 */
class ForwardingSecretResolverTest {
	private static final LunaLogger LOGGER = LunaLogger.forLogger(Logger.getLogger("test"), false);

	@Test
	void readsFabricProxyLiteTopLevelKey(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("FabricProxy-Lite.toml"), """
			hackOnlineMode = true
			secret = "fabric-secret"
			""");

		assertEquals("fabric-secret", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void readsProxyCompatibleForgeV2SectionKey(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("proxy-compatible-forge.toml"), """
			version = 2.0

			[forwarding]
				enabled = true
				mode = "MODERN"
				secret = "forge-secret"
			""");

		assertEquals("forge-secret", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void readsLegacyPcfCommonKey(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("pcf-common.toml"), """
			modernForwarding = true
			forwardingSecret = "forge-secret"
			""");

		assertEquals("forge-secret", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void readsNeoVelocitySectionKey(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("neovelocity-common.toml"), """
			[general]
			secret = "wrong-section"

			[forwarding]
			forwarding-secret = "neo-secret"
			""");

		assertEquals("neo-secret", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void followsSecretFileIndirection(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("forwarding.secret"), "  file-secret\n");
		Files.writeString(configDir.resolve("neovelocity-common.toml"), """
			[forwarding]
			forwarding-secret-type = "FILE"
			forwarding-secret-file = "forwarding.secret"
			""");

		assertEquals("file-secret", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void ignoresCommentedOutSecret(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("FabricProxy-Lite.toml"), """
			# secret = "commented"
			secret = "real-secret" # trailing note
			""");

		assertEquals("real-secret", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void answersBlankWhenNothingIsConfigured(@TempDir Path configDir) {
		assertEquals("", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}

	@Test
	void skipsAPresentButEmptyConfigAndKeepsLooking(@TempDir Path configDir) throws IOException {
		Files.writeString(configDir.resolve("neovelocity-common.toml"), "[forwarding]\n");
		Files.writeString(configDir.resolve("FabricProxy-Lite.toml"), "secret = \"second-chance\"\n");

		assertEquals("second-chance", ForwardingSecretResolver.resolve(configDir, LOGGER));
	}
}
