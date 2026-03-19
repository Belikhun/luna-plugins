package dev.belikhun.luna.countdown.fabric.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricCountdownFormatTest {

	@Test
	void parsesDurationWithSuffixes() {
		assertEquals(30, FabricCountdownFormat.parseTime("30"));
		assertEquals(45, FabricCountdownFormat.parseTime("45s"));
		assertEquals(120, FabricCountdownFormat.parseTime("2m"));
		assertEquals(7200, FabricCountdownFormat.parseTime("2h"));
		assertEquals(172800, FabricCountdownFormat.parseTime("2d"));
	}

	@Test
	void invalidDurationReturnsMinusOne() {
		assertEquals(-1, FabricCountdownFormat.parseTime(null));
		assertEquals(-1, FabricCountdownFormat.parseTime(""));
		assertEquals(-1, FabricCountdownFormat.parseTime("abc"));
	}

	@Test
	void readableTimeContainsExpectedUnits() {
		assertTrue(FabricCountdownFormat.readableTime(30).contains("s"));
		assertTrue(FabricCountdownFormat.readableTime(600).contains("m"));
		assertTrue(FabricCountdownFormat.readableTime(7500).contains("h"));
	}
}
