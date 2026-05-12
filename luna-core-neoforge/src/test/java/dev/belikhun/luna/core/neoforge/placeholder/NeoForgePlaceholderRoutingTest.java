package dev.belikhun.luna.core.neoforge.placeholder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NeoForgePlaceholderRoutingTest {
	@Test
	void unwrapsWrappedIdentifiers() {
		assertEquals("luna_player_status_⏺", NeoForgePlaceholderRouting.unwrapIdentifier("%luna_player_status_⏺%"));
		assertEquals("spark_tickduration_10s", NeoForgePlaceholderRouting.unwrapIdentifier(" spark_tickduration_10s "));
		assertEquals("", NeoForgePlaceholderRouting.unwrapIdentifier("  "));
	}

	@Test
	void routesExplicitNamespaceAndPreservesFlexibleArgumentTail() {
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			new TestNamespaceProvider(Set.of("luna")),
			new TestNamespaceProvider(Set.of(""))
		));

		NeoForgePlaceholderRoute<TestNamespaceProvider> route = NeoForgePlaceholderRouting.resolve("%luna_player_status_⏺%", providersByNamespace);

		assertNotNull(route);
		assertEquals("luna", route.rawNamespace());
		assertEquals("luna", route.normalizedNamespace());
		assertEquals("player_status_⏺", route.rawParams());
		assertEquals("player_status_⏺", route.normalizedParams());
		assertFalse(route.safeVariant());
	}

	@Test
	void normalizesRegisteredNamespacesAndKeepsRegistrationOrder() {
		TestNamespaceProvider first = new TestNamespaceProvider(Set.of(" Luna "));
		TestNamespaceProvider second = new TestNamespaceProvider(Set.of(""));
		TestNamespaceProvider third = new TestNamespaceProvider(Set.of(""));
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(first, second, third));

		assertEquals(List.of(first), providersByNamespace.get("luna"));
		assertEquals(List.of(second, third), providersByNamespace.get(""));
	}

	@Test
	void exposesImmutableRegisteredProviderCollections() {
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			new TestNamespaceProvider(Set.of("luna")),
			new TestNamespaceProvider(Set.of(""))
		));

		assertThrows(UnsupportedOperationException.class, () -> providersByNamespace.put("spark", List.of()));
		assertThrows(UnsupportedOperationException.class, () -> providersByNamespace.get("luna").add(new TestNamespaceProvider(Set.of("other"))));
	}

	@Test
	void preservesRawDynamicArgumentsAndNormalizesLookupArguments() {
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			new TestNamespaceProvider(Set.of("luna"))
		));

		NeoForgePlaceholderRoute<TestNamespaceProvider> route = NeoForgePlaceholderRouting.resolve("%LuNa_player_status_★Wide%", providersByNamespace);

		assertNotNull(route);
		assertEquals("LuNa", route.rawNamespace());
		assertEquals("luna", route.normalizedNamespace());
		assertEquals("player_status_★Wide", route.rawParams());
		assertEquals("player_status_★wide", route.normalizedParams());
	}

	@Test
	void fallsBackToDefaultProvidersForBareIdentifiers() {
		TestNamespaceProvider defaultOne = new TestNamespaceProvider(Set.of(""));
		TestNamespaceProvider defaultTwo = new TestNamespaceProvider(Set.of(""));
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(defaultOne, defaultTwo));

		NeoForgePlaceholderRoute<TestNamespaceProvider> route = NeoForgePlaceholderRouting.resolve("%player_displayname%", providersByNamespace);

		assertNotNull(route);
		assertEquals("", route.rawNamespace());
		assertEquals("", route.normalizedNamespace());
		assertEquals("player_displayname", route.rawParams());
		assertEquals(List.of(defaultOne, defaultTwo), route.providers());
	}

	@Test
	void stripsSafeSuffixOnlyForLunaNamespace() {
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			new TestNamespaceProvider(Set.of("luna")),
			new TestNamespaceProvider(Set.of("spark"))
		));

		NeoForgePlaceholderRoute<TestNamespaceProvider> lunaRoute = NeoForgePlaceholderRouting.resolve("%luna_system_cpu_safe%", providersByNamespace);
		NeoForgePlaceholderRoute<TestNamespaceProvider> sparkRoute = NeoForgePlaceholderRouting.resolve("%spark_system_cpu_safe%", providersByNamespace);

		assertNotNull(lunaRoute);
		assertEquals("system_cpu", lunaRoute.normalizedParams());
		assertTrue(lunaRoute.safeVariant());
		assertNotNull(sparkRoute);
		assertEquals("system_cpu_safe", sparkRoute.normalizedParams());
		assertFalse(sparkRoute.safeVariant());
	}

	@Test
	void escapesPercentSignsForSafePlaceholderValues() {
		assertEquals("50:percent:", NeoForgePlaceholderEscaping.escapePercents("50%"));
		assertEquals("CPU 12.5:percent::percent:", NeoForgePlaceholderEscaping.escapePercents("CPU 12.5%%"));
		assertEquals("", NeoForgePlaceholderEscaping.escapePercents(null));
		assertEquals("plain text", NeoForgePlaceholderEscaping.escapePercents("plain text"));
	}

	@Test
	void acceptsMultipleExplicitProvidersForSameNamespace() {
		TestNamespaceProvider imported = new TestNamespaceProvider(Set.of("luna"));
		TestNamespaceProvider builtin = new TestNamespaceProvider(Set.of("LUNA"));

		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			imported,
			builtin
		));

		assertEquals(List.of(imported, builtin), providersByNamespace.get("luna"));
	}

	@Test
	void acceptsMultipleDefaultProviders() {
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			new TestNamespaceProvider(Set.of("")),
			new TestNamespaceProvider(Set.of()),
			new TestNamespaceProvider(Set.of("luna"))
		));

		assertEquals(2, providersByNamespace.get("").size());
		assertEquals(1, providersByNamespace.get("luna").size());
	}

	@Test
	void returnsNullWhenNoMatchingRouteExists() {
		Map<String, List<TestNamespaceProvider>> providersByNamespace = NeoForgePlaceholderRouting.indexProvidersByNamespace(List.of(
			new TestNamespaceProvider(Set.of("luna"))
		));

		assertNull(NeoForgePlaceholderRouting.resolve("%unknown_value%", providersByNamespace));
		assertNull(NeoForgePlaceholderRouting.resolve("  ", providersByNamespace));
	}

	private record TestNamespaceProvider(Set<String> namespaces) implements NeoForgePlaceholderNamespaceProvider {
	}
}
