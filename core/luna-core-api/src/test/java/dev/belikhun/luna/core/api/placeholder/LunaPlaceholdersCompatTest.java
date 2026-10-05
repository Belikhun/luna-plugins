package dev.belikhun.luna.core.api.placeholder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code %luna_*%} found in production configs must resolve through the
 * new engine. The fixture is the contract; a placeholder is added to it when it
 * is added to a config.
 */
final class LunaPlaceholdersCompatTest {
	private PlaceholderEngine<String> engine;
	private TestHost host;

	@BeforeEach
	void setUp() {
		host = new TestHost();
		PlaceholderRegistry<String> registry = new PlaceholderRegistry<>(message -> {
			throw new AssertionError(message);
		});
		registry.register("core", LunaPlaceholders.<String>specs());
		engine = new PlaceholderEngine<>(registry, host, message -> {
			throw new AssertionError(message);
		});
		engine.refreshServer();
		engine.refreshPlayer("Pilk");
	}

	@Test
	void everyProductionLunaPlaceholderResolves() throws IOException {
		List<String> failures = new ArrayList<>();

		for (String line : fixtureLines()) {
			if (!line.startsWith("%luna_")) {
				continue;
			}

			Resolution<String> resolution = engine.resolve(PlaceholderGrammar.parse(line), "Pilk");

			if (!resolution.hasValue() || resolution.value().isBlank()) {
				failures.add(line + " -> " + resolution.status() + " " + resolution.error());
			}
		}

		assertTrue(failures.isEmpty(), "unresolved: " + failures);
	}

	@Test
	void valuesFollowPaperShapes() {
		assertEquals("42.6", engine.resolveText("%luna_system_cpu%", "Pilk"));
		assertEquals("19.87", engine.resolveText("%luna_tps%", "Pilk"));
		assertEquals("17", engine.resolveText("%luna_latency%", "Pilk"));
		assertEquals("mayphatdien", engine.resolveText("%luna_host_name%", "Pilk"));
		assertEquals("survival", engine.resolveText("%luna_current_server%", "Pilk"));
		assertEquals("ONLINE", engine.resolveText("%luna_status%", "Pilk"));
		assertEquals("45", engine.resolveText("%luna_player_ping%", "Pilk"));
		assertEquals("0", engine.resolveText("%luna_player_ping%", null));
		assertEquals("<c:#ff5555>⏺<reset>", engine.resolveText("%luna_player_status_⏺%", "Pilk"));
		assertEquals("<c:#ff5555>▋<reset>", engine.resolveText("%luna_player_status%", "Pilk"));
		assertEquals("<white>❌<reset>", engine.resolveText("%luna_player_status_⏺%", null));
		assertEquals("Mưa", engine.resolveText("%luna_world_survival_weather%", null));
		assertEquals("1m 30s", engine.resolveText("%luna_world_survival_weatherduration%", null));
		assertEquals("unknown:nowhere", engine.resolveText("%luna_world_nowhere_weather%", null));
	}

	@Test
	void safeSuffixEscapesPercentSignsAndTagFormBindsArguments() {
		String cpuBar = engine.resolveText("%luna_system_cpu_bar_safe%", "Pilk");
		assertNotNull(cpuBar);
		assertFalse(cpuBar.contains("%"), cpuBar);
		assertTrue(cpuBar.contains("％"), cpuBar);

		assertEquals(
			engine.resolveText("%luna_player_status_⏺%", "Pilk"),
			engine.resolveText("<luna_player_status:'⏺'>", "Pilk")
		);
		assertEquals(
			engine.resolveText("%luna_tps_bar_10%", "Pilk"),
			engine.resolveText("<luna_tps_bar:10>", "Pilk")
		);
	}

	@Test
	void barVariantsAndWidthsAreDistinct() {
		String full = engine.resolveText("%luna_ram_bar_only_80%", null);
		String narrow = engine.resolveText("%luna_ram_bar_only_10%", null);
		String clamped = engine.resolveText("%luna_ram_bar_only_999%", null);
		String valueOnly = engine.resolveText("%luna_ram_bar_value_only%", null);

		assertNotNull(full);
		assertNotNull(narrow);
		assertTrue(full.length() > narrow.length());
		assertEquals(engine.resolveText("%luna_ram_bar_only_120%", null), clamped);
		assertTrue(valueOnly.contains("mb"), valueOnly);
		assertTrue(engine.resolve(PlaceholderGrammar.parse("%luna_ram_bar_only_abc%"), null).status() == Resolution.Status.UNMATCHED);
	}

	@Test
	void nestedBracesExpandThroughTheForeignHost() {
		host.foreignValues.put("luckperms_prefix", "<red>[Admin]</red>");

		assertEquals("[Admin]", engine.resolveText("%luna_stripcolor_mm_{luckperms_prefix}%", "Pilk"));
		assertEquals("&c[Admin]", engine.resolveText("%luna_mm2l_{luckperms_prefix}%", "Pilk"));
		assertEquals("<red>[Admin]</red>", engine.resolve(PlaceholderGrammar.parse("%luckperms_prefix%"), "Pilk").value());
		assertEquals(Resolution.Status.FOREIGN, engine.resolve(PlaceholderGrammar.parse("%luckperms_prefix%"), "Pilk").status());
	}

	@Test
	void unknownKeysStayUnmatchedRatherThanBlank() {
		Resolution<String> resolution = engine.resolve(PlaceholderGrammar.parse("%luna_no_such_key%"), "Pilk");

		assertEquals(Resolution.Status.UNMATCHED, resolution.status());
		assertFalse(resolution.hasValue());
	}

	private static List<String> fixtureLines() throws IOException {
		try (InputStream input = LunaPlaceholdersCompatTest.class.getResourceAsStream("/placeholders/production-compat.txt")) {
			assertNotNull(input, "fixture missing");
			List<String> lines = new ArrayList<>();

			for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String trimmed = line.trim();

				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					lines.add(trimmed);
				}
			}

			return lines;
		}
	}
}
