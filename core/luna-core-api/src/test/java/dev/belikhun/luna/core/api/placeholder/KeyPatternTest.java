package dev.belikhun.luna.core.api.placeholder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KeyPatternTest {
	@Test
	void literalOnlyPatternMatchesExactlyAndCaseInsensitively() {
		KeyPattern pattern = KeyPattern.parse("current_server");

		assertTrue(pattern.match(List.of("current", "server"), List.of()).isPresent());
		assertTrue(pattern.match(List.of("Current", "SERVER"), List.of()).isPresent());
		assertTrue(pattern.match(List.of("current"), List.of()).isEmpty());
		assertTrue(pattern.match(List.of("current", "server", "x"), List.of()).isEmpty());
	}

	@Test
	void optionalWidthBindsFromTokenOrArgumentOrDefaultsToNothing() {
		KeyPattern pattern = KeyPattern.parse("tps_bar[_<width:width>]");

		assertEquals(Map.of(), pattern.match(List.of("tps", "bar"), List.of()).orElseThrow());
		assertEquals(Map.of("width", "10"), pattern.match(List.of("tps", "bar", "10"), List.of()).orElseThrow());
		assertEquals(Map.of("width", "10"), pattern.match(List.of("tps", "bar"), List.of("10")).orElseThrow());
		assertTrue(pattern.match(List.of("tps", "bar", "only"), List.of()).isEmpty());
		assertTrue(pattern.match(List.of("tps", "bar", "only", "40"), List.of()).isEmpty());
	}

	@Test
	void textParameterSwallowsUnderscoresUpToTheTrailingLiteral() {
		KeyPattern pattern = KeyPattern.parse("world_<world:text>_weather");

		assertEquals(Map.of("world", "my_cool_world"), pattern.match(List.of("world", "my", "cool", "world", "weather"), List.of()).orElseThrow());
		assertTrue(pattern.match(List.of("world", "survival", "weathericon"), List.of()).isEmpty());
	}

	@Test
	void restParameterTakesEverythingIncludingBraces() {
		KeyPattern pattern = KeyPattern.parse("mm2l_<value:rest>");
		Optional<Map<String, String>> bound = pattern.match(List.of("mm2l", "{luckperms", "prefix}"), List.of());

		assertEquals("{luckperms_prefix}", bound.orElseThrow().get("value"));
		assertTrue(pattern.match(List.of("mm2l"), List.of()).isEmpty());
	}

	@Test
	void optionalRestIsAbsentWhenNothingFollows() {
		KeyPattern pattern = KeyPattern.parse("player_status[_<dot:rest>]");

		assertEquals(Map.of(), pattern.match(List.of("player", "status"), List.of()).orElseThrow());
		assertEquals(Map.of("dot", "⏺"), pattern.match(List.of("player", "status", "⏺"), List.of()).orElseThrow());
		assertEquals(Map.of("dot", "⏺"), pattern.match(List.of("player", "status"), List.of("⏺")).orElseThrow());
		assertEquals(Map.of("dot", "a_b"), pattern.match(List.of("player", "status", "a", "b"), List.of()).orElseThrow());
	}

	@Test
	void rejectsUnusedExplicitArguments() {
		KeyPattern pattern = KeyPattern.parse("balance");

		assertTrue(pattern.match(List.of("balance"), List.of("extra")).isEmpty());
	}

	@Test
	void countsLiteralsForSpecificity() {
		assertEquals(2, KeyPattern.parse("tps_bar[_<width:width>]").literalCount());
		assertEquals(3, KeyPattern.parse("tps_bar_only[_<width:width>]").literalCount());
		assertEquals(4, KeyPattern.parse("tps_bar_value_only").literalCount());
	}

	@Test
	void refusesMalformedPatterns() {
		assertThrows(IllegalArgumentException.class, () -> KeyPattern.parse("mm2l_<value:rest>_tail"));
		assertThrows(IllegalArgumentException.class, () -> KeyPattern.parse("open_<value"));
	}
}
