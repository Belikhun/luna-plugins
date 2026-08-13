package dev.belikhun.luna.legacy.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What luna's messages look like once 1.12.2 has had them.
 *
 * These pin a *lossy* conversion, which is the point: the losses are behaviour an
 * operator will see and ask about, so they are written down as assertions rather
 * than left to be rediscovered on a live server.
 */
class LegacyTextTest {
	private static final char SECTION = '§';

	@Test
	void namedColoursBecomeTheirLegacyCodes() {
		assertEquals(SECTION + "aready", LegacyText.legacy("<green>ready"));
		assertEquals(SECTION + "cfailed", LegacyText.legacy("<red>failed"));
	}

	@Test
	void formattingSurvives() {
		assertEquals(SECTION + "l" + "bold", LegacyText.legacy("<bold>bold"));
	}

	/**
	 * The whole reason this class exists. luna's palette is hex, and 1.12.2 has
	 * sixteen colours - so a hex colour has to arrive as the nearest of them rather
	 * than as a code the client cannot read.
	 */
	@Test
	void hexIsDownsampledToTheSixteen() {
		String rendered = LegacyText.legacy("<color:#5FE2C5>luna");

		assertFalse(rendered.contains("#"), "a hex code reached a 1.12.2 client: " + rendered);
		assertEquals(SECTION, rendered.charAt(0));
		// #5FE2C5 is a pale cyan; aqua is the nearest of the sixteen
		assertEquals(SECTION + "bluna", rendered);
	}

	/**
	 * A gradient becomes one solid colour, its first stop.
	 *
	 * Per-character downsampling is what this replaced. It was correct and unusable:
	 * one colour run per character, so nine characters carried eighteen bytes of `§`
	 * codes, and a container title built that way arrived mangled on a 1.12.2 client.
	 */
	@Test
	void aGradientCollapsesToItsFirstStop() {
		String rendered = LegacyText.legacy("<gradient:#5FE2C5:#9BC1F9>FORGE</gradient>");

		assertFalse(rendered.contains("#"), rendered);
		assertEquals("FORGE", rendered.replaceAll(SECTION + ".", ""));

		// #5FE2C5 is a pale cyan, so the whole run is aqua and there is exactly one
		assertEquals(SECTION + "bFORGE", rendered);
	}

	/** The same collapse, with a phase argument on the end. */
	@Test
	void aGradientWithAPhaseStillCollapses() {
		assertEquals(SECTION + "bFORGE", LegacyText.legacy("<gradient:#5FE2C5:#9BC1F9:0.5>FORGE</gradient>"));
	}

	/** An argument-less gradient has no stop to take, so it renders unstyled white. */
	@Test
	void aBareGradientFallsBackToWhite() {
		assertEquals(SECTION + "fFORGE", LegacyText.legacy("<gradient>FORGE</gradient>"));
	}

	/** Ordinary colours are untouched by the collapse. */
	@Test
	void nonGradientColoursAreUnaffected() {
		assertEquals(SECTION + "cred" + SECTION + "6gold", LegacyText.legacy("<red>red</red><gold>gold</gold>"));
	}

	@Test
	void plainTextIsUnchangedAndNullIsEmpty() {
		assertEquals("hello", LegacyText.legacy("hello"));
		assertEquals("", LegacyText.legacy((String) null));
	}
}
