package dev.belikhun.luna.pack.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackDefinitionTest {
	@Test
	void wildcardIncludeCanExcludeSpecificServer() {
		PackDefinition definition = definition(List.of("*", "!sandbox"));

		assertTrue(definition.matchesServer("survival"));
		assertFalse(definition.matchesServer("sandbox"));
	}

	@Test
	void exclusionStillWinsWhenListedBeforeWildcard() {
		PackDefinition definition = definition(List.of("!sandbox", "*"));

		assertFalse(definition.matchesServer("sandbox"));
		assertTrue(definition.matchesServer("survival"));
	}

	@Test
	void normalizeServerRuleSupportsNegatedAllAlias() {
		assertEquals("!*", PackDefinition.normalizeServerRule(" !All "));
		assertEquals("!sandbox", PackDefinition.normalizeServerRule(" !Sandbox "));
		assertNull(PackDefinition.normalizeServerRule("!   "));
	}

	private PackDefinition definition(List<String> servers) {
		return new PackDefinition(
			"test-pack",
			"test-pack.zip",
			0,
			false,
			true,
			servers,
			Path.of("test-pack.yml")
		);
	}
}
