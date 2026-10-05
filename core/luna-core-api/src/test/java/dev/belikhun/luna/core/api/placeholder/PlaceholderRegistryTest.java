package dev.belikhun.luna.core.api.placeholder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaceholderRegistryTest {
	private static PlaceholderSpec<String> spec(String namespace, String key, String value) {
		return PlaceholderSpec.of(namespace, key, PlaceholderScope.SERVER, PlaceholderRefresh.STATIC, "", request -> value);
	}

	@Test
	void mostLiteralPatternWins() {
		PlaceholderRegistry<String> registry = new PlaceholderRegistry<>(null);
		registry.register("core", spec("luna", "tps_bar[_<width:width>]", "bar"));
		registry.register("core", spec("luna", "tps_bar_only[_<width:width>]", "only"));
		registry.register("core", spec("luna", "tps_bar_value_only", "value"));

		assertEquals("bar", registry.match(PlaceholderRef.of("%luna_tps_bar_40%")).orElseThrow().spec().resolver().resolve(null));
		assertEquals("only", registry.match(PlaceholderRef.of("%luna_tps_bar_only_40%")).orElseThrow().spec().resolver().resolve(null));
		assertEquals("only", registry.match(PlaceholderRef.of("%luna_tps_bar_only%")).orElseThrow().spec().resolver().resolve(null));
		assertEquals("value", registry.match(PlaceholderRef.of("%luna_tps_bar_value_only%")).orElseThrow().spec().resolver().resolve(null));
		assertTrue(registry.match(PlaceholderRef.of("%luna_tps_bar_nope%")).isEmpty());
	}

	@Test
	void sameOwnerReplacesAndOtherOwnerIsRefused() {
		List<String> warnings = new ArrayList<>();
		PlaceholderRegistry<String> registry = new PlaceholderRegistry<>(warnings::add);
		Object vault = new Object();
		Object other = new Object();

		assertTrue(registry.register(vault, spec("lunavault", "balance", "1")));
		assertTrue(registry.register(vault, spec("lunavault", "balance", "2")));
		assertFalse(registry.register(other, spec("lunavault", "balance", "3")));

		assertEquals(1, registry.specs("lunavault").size());
		assertEquals("2", registry.match(PlaceholderRef.of("%lunavault_balance%")).orElseThrow().spec().resolver().resolve(null));
		assertEquals(1, warnings.size());
	}

	@Test
	void unregisterDropsOnlyThatOwner() {
		PlaceholderRegistry<String> registry = new PlaceholderRegistry<>(null);
		Object vault = new Object();
		registry.register("core", spec("luna", "tps", "20"));
		registry.register(vault, spec("lunavault", "balance", "1"));
		registry.register(vault, spec("lunavault", "rank", "1"));

		assertEquals(2, registry.unregister(vault));
		assertFalse(registry.claims("lunavault"));
		assertTrue(registry.claims("LUNA"));
		assertEquals(List.of("luna"), new ArrayList<>(registry.namespaces()));
	}
}
