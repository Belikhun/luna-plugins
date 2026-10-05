package dev.belikhun.luna.core.api.placeholder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PlaceholderAPI stand-ins a mod-loader backend answers. */
final class CompatPlaceholdersTest {
	private PlaceholderEngine<String> engine;

	@BeforeEach
	void setUp() {
		PlaceholderRegistry<String> registry = new PlaceholderRegistry<>(message -> {
			throw new AssertionError(message);
		});
		registry.register("core", LunaPlaceholders.<String>specs());
		registry.register("compat", CompatPlaceholders.<String>specs());
		engine = new PlaceholderEngine<>(registry, new TestHost(), message -> {
			throw new AssertionError(message);
		});
		engine.refreshServer();
		engine.refreshPlayer("Pilk");
	}

	@Test
	void playerAndPermissionKeysReadTheViewerSample() {
		assertEquals("10", engine.resolveText("%player_x%", "Pilk"));
		assertEquals("17.5", engine.resolveText("%player_health%", "Pilk"));
		assertEquals("18", engine.resolveText("%player_health_rounded%", "Pilk"));
		assertEquals("survival_nether", engine.resolveText("%player_world%", "Pilk"));
		assertEquals("survival_nether", engine.resolveText("%world%", "Pilk"));
		assertEquals("<red>[Admin]</red>", engine.resolveText("%luckperms_prefix%", "Pilk"));
		assertEquals("admin", engine.resolveText("%vault_primary_group%", "Pilk"));
		assertEquals(Resolution.Status.EMPTY, engine.resolve(PlaceholderGrammar.parse("%player_x%"), null).status());
	}

	@Test
	void serverKeysReadTheServerSample() {
		assertEquals("mayphatdien", engine.resolveText("%server_name%", null));
		assertEquals("1234", engine.resolveText("%server_total_entities%", null));
		assertEquals("19.87", engine.resolveText("%server_tps%", null));

		String time = engine.resolveText("%server_time_HH:mm%", null);
		assertNotNull(time);
		assertTrue(time.matches("\\d{2}:\\d{2}"), time);
	}

	@Test
	void sparkFallsBackToTheTickReadingWhenSparkIsAbsent() {
		String value = engine.resolveText("%spark_tickduration_10s%", null);
		assertNotNull(value);
		assertTrue(value.contains("12.3"), value);
	}

	@Test
	void compatNamespacesDoNotCollideWithLuna() {
		assertEquals("19.87", engine.resolveText("%luna_tps%", null));
		assertEquals("45", engine.resolveText("%luna_player_ping%", "Pilk"));
		assertEquals("45", engine.resolveText("%player_ping%", "Pilk"));
	}
}
