package dev.belikhun.luna.glyph.config;

import dev.belikhun.luna.glyph.model.GlyphDefinition;

import java.util.Map;

public record GlyphPluginState(
	GlyphPackConfig pack,
	Map<String, GlyphDefinition> glyphs,
	Map<String, String> placeholderValues
) {
}
