package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricBackendPlaceholderResolverTest {

	@Test
	void appliesInternalValuesWithoutExternalBridge() {
		FabricBackendPlaceholderResolver resolver = new FabricBackendPlaceholderResolver(
			FabricBackendPlaceholderResolver.PlaceholderBridge.noop(),
			java.util.List.of()
		);

		PlaceholderResolutionRequest request = new PlaceholderResolutionRequest(
			UUID.randomUUID(),
			"Belikhun",
			"hub",
			"Hello {sender_name} from %server_name%",
			Map.of("custom", "value")
		);

		var result = resolver.resolve(request);
		assertEquals("Hello Belikhun from hub", result.resolvedContent());
		assertEquals("value", result.exportedValues().get("custom"));
	}

	@Test
	void resolvesExportedTokensThroughBridge() {
		FabricBackendPlaceholderResolver.PlaceholderBridge bridge = new FabricBackendPlaceholderResolver.PlaceholderBridge() {
			@Override
			public boolean isAvailable() {
				return true;
			}

			@Override
			public String resolveText(UUID playerId, String text) {
				if ("%player_displayname%".equals(text)) {
					return "BelikhunDisplay";
				}
				if ("%luckperms_prefix%".equals(text)) {
					return "[ADMIN]";
				}
				return text.replace("%player_displayname%", "BelikhunDisplay");
			}
		};

		FabricBackendPlaceholderResolver resolver = new FabricBackendPlaceholderResolver(
			bridge,
			java.util.List.of("player_displayname", "luckperms_prefix")
		);

		PlaceholderResolutionRequest request = new PlaceholderResolutionRequest(
			UUID.randomUUID(),
			"Belikhun",
			"hub",
			"Xin chao %player_displayname%",
			Map.of()
		);

		var result = resolver.resolve(request);
		assertEquals("Xin chao BelikhunDisplay", result.resolvedContent());
		assertEquals("BelikhunDisplay", result.exportedValues().get("player_displayname"));
		assertEquals("[ADMIN]", result.exportedValues().get("luckperms_prefix"));
		assertTrue(result.exportedValues().containsKey("sender_name"));
	}
}
