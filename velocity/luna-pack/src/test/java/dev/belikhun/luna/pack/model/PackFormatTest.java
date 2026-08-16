package dev.belikhun.luna.pack.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFormatTest {
	@Test
	void parsesEveryDeclarationShape() {
		assertEquals(new PackFormat(64, 0), PackFormat.parse(64));
		assertEquals(new PackFormat(69, 0), PackFormat.parse(List.of(69, 0)));
		assertEquals(new PackFormat(75, 2), PackFormat.parse(List.of(75, 2)));
		assertEquals(new PackFormat(75, 0), PackFormat.parse("75"));
		assertEquals(new PackFormat(75, 2), PackFormat.parse("75.2"));
	}

	@Test
	void rejectsMalformedValues() {
		assertNull(PackFormat.parse(null));
		assertNull(PackFormat.parse("abc"));
		assertNull(PackFormat.parse("75.x"));
		assertNull(PackFormat.parse(List.of("a", "b")));
		assertNull(PackFormat.parse(List.of(75)));
	}

	@Test
	void comparesByMajorThenMinor() {
		assertTrue(new PackFormat(69, 0).isAfter(new PackFormat(64, 0)));
		assertTrue(new PackFormat(69, 1).isAfter(new PackFormat(69, 0)));
		assertFalse(new PackFormat(69, 0).isAfter(new PackFormat(69, 0)));
	}

	@Test
	void rangeContainsItsBoundsInclusive() {
		PackFormatRange range = new PackFormatRange(new PackFormat(15, 0), new PackFormat(64, 0), "supported_formats", false);

		assertTrue(range.contains(new PackFormat(15, 0)));
		assertTrue(range.contains(new PackFormat(64, 0)));
		assertFalse(range.contains(new PackFormat(14, 0)));
		assertFalse(range.contains(new PackFormat(64, 1)));
		assertFalse(range.contains(new PackFormat(69, 0)));
	}

	@Test
	void emptyRangeMatchesNothing() {
		PackFormatRange range = new PackFormatRange(new PackFormat(70, 0), new PackFormat(64, 0), "pack_format", true);

		assertTrue(range.isEmpty());
		assertFalse(range.contains(new PackFormat(70, 0)));
		assertFalse(range.contains(new PackFormat(64, 0)));
	}

	@Test
	void rendersLegacyAndRenumberedFormats() {
		assertEquals("64", new PackFormat(64, 0).render());
		assertEquals("75.2", new PackFormat(75, 2).render());
		assertEquals("15-64", new PackFormatRange(new PackFormat(15, 0), new PackFormat(64, 0), "x", false).render());
		assertEquals("69", new PackFormatRange(new PackFormat(69, 0), new PackFormat(69, 0), "x", false).render());
	}
}
