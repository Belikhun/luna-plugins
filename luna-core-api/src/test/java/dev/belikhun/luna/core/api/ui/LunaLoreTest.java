package dev.belikhun.luna.core.api.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LunaLoreTest {
	@Test
	void wrapsAccentedWordsAsValidText() {
		List<String> lines = LunaLore.wrapLoreLine("điện thoại", 1);
		assertEquals(List.of("điện", " thoại"), lines);
	}

	@Test
	void keepsCurrencySymbolWithNeighboringText() {
		List<String> lines = LunaLore.wrapLoreLine("aaaa bbbb ₫ cccc", 1);
		assertEquals(List.of("aaaa", " bbbb ₫", " cccc"), lines);
	}

	@Test
	void ignoresShortWordsForWrapCounting() {
		List<String> lines = LunaLore.wrapLoreLine("mua bán đồ xịn phẩm chất", 1);
		assertEquals(List.of("mua bán đồ xịn phẩm", " chất"), lines);
	}

	@Test
	void preservesAndReopensMiniMessageTagsWhenWrapping() {
		List<String> lines = LunaLore.wrapLoreLine("<gold>aaaa bbbb cccc</gold>", 1);
		assertEquals(List.of("<gold>aaaa</gold>", "<gold> bbbb</gold>", "<gold> cccc</gold>"), lines);
	}
}

