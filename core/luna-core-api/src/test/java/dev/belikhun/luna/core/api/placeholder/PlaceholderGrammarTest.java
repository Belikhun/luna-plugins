package dev.belikhun.luna.core.api.placeholder;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaceholderGrammarTest {
	@Test
	void parsesPercentFormIntoNamespaceAndTokens() {
		PlaceholderRef ref = PlaceholderGrammar.parse("%Luna_TPS_bar_10%");

		assertEquals("luna", ref.namespace());
		assertEquals(List.of("TPS", "bar", "10"), ref.tokens());
		assertEquals(PlaceholderSyntax.PERCENT, ref.syntax());
		assertFalse(ref.safe());
		assertEquals("luna_tps_bar_10", ref.normalizedIdentifier());
	}

	@Test
	void recognisesSafeSuffixOnAnyNamespace() {
		assertTrue(PlaceholderGrammar.parse("%luna_system_cpu_safe%").safe());
		assertTrue(PlaceholderGrammar.parse("%lunavault_balance_safe%").safe());
		assertEquals(List.of("balance"), PlaceholderGrammar.parse("%lunavault_balance_safe%").tokens());

		// a key that is literally "safe" is not a suffix
		PlaceholderRef bare = PlaceholderGrammar.parse("%luna_safe%");
		assertFalse(bare.safe());
		assertEquals(List.of("safe"), bare.tokens());
	}

	@Test
	void keepsGlyphArgumentsInPercentForm() {
		PlaceholderRef ref = PlaceholderGrammar.parse("%luna_player_status_⏺%");

		assertEquals(List.of("player", "status", "⏺"), ref.tokens());
		assertEquals("%luna_player_status_⏺%", PlaceholderGrammar.format(ref, PlaceholderSyntax.PERCENT));
	}

	@Test
	void parsesMiniMessageTagWithQuotedArguments() {
		PlaceholderRef ref = PlaceholderGrammar.parse("<luna_player_status:'⏺'>");

		assertEquals("luna", ref.namespace());
		assertEquals(List.of("player", "status"), ref.tokens());
		assertEquals(List.of("⏺"), ref.args());
		assertEquals(PlaceholderSyntax.MINI_TAG, ref.syntax());

		PlaceholderRef safe = PlaceholderGrammar.parse("<luna_tps_bar:10:safe>");
		assertTrue(safe.safe());
		assertEquals(List.of("10"), safe.args());
	}

	@Test
	void movesTagUnsafeTokensIntoArgumentsWhenFormattingAsTag() {
		PlaceholderRef ref = PlaceholderGrammar.parse("%luna_player_status_⏺%");

		assertEquals("<luna_player_status:'⏺'>", PlaceholderGrammar.format(PlaceholderGrammar.tagSafe(ref), PlaceholderSyntax.MINI_TAG));
		assertEquals("<luna_tps_bar_10>", PlaceholderGrammar.format(PlaceholderGrammar.parse("%luna_tps_bar_10%"), PlaceholderSyntax.MINI_TAG));
	}

	@Test
	void formatsArgumentsBackIntoTokensForPercentForm() {
		PlaceholderRef ref = PlaceholderGrammar.parse("<luna_tps_bar:10:safe>");

		assertEquals("%luna_tps_bar_10_safe%", PlaceholderGrammar.format(ref, PlaceholderSyntax.PERCENT));
		assertEquals("{luna_tps_bar_10_safe}", PlaceholderGrammar.format(ref, PlaceholderSyntax.BRACE));
	}

	@Test
	void parsesBraceAndBareForms() {
		assertEquals(PlaceholderSyntax.BRACE, PlaceholderGrammar.parse("{luckperms_prefix}").syntax());
		assertEquals("luckperms", PlaceholderGrammar.parse("luckperms_prefix").namespace());
		assertEquals("", PlaceholderGrammar.parse("   ").namespace());
	}

	@Test
	void scansMixedTextWithoutReadingPercentagesAsPlaceholders() {
		String text = "50% off <gold>%luna_tps%</gold> and {lunavault_balance} at %server_time_dd/MM/yyyy hh:mm:ss a% <luna_player_status:'⏺'>";
		List<PlaceholderGrammar.Segment> segments = PlaceholderGrammar.scan(text, EnumSet.allOf(PlaceholderSyntax.class));

		List<String> refs = segments.stream().filter(PlaceholderGrammar.Segment::isRef).map(PlaceholderGrammar.Segment::source).toList();
		assertEquals(List.of("%luna_tps%", "{lunavault_balance}", "%server_time_dd/MM/yyyy hh:mm:ss a%", "<luna_player_status:'⏺'>"), refs);
		assertEquals("50% off <gold>", segments.get(0).literal());
	}

	@Test
	void scanningForPercentOnlyLeavesTagsAlone() {
		List<PlaceholderGrammar.Segment> segments = PlaceholderGrammar.scan("<gold>%luna_tps%</gold>", EnumSet.of(PlaceholderSyntax.PERCENT));

		assertEquals(3, segments.size());
		assertEquals("<gold>", segments.get(0).literal());
		assertTrue(segments.get(1).isRef());
		assertEquals("</gold>", segments.get(2).literal());
	}
}
