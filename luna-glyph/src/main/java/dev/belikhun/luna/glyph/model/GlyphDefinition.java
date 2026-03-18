package dev.belikhun.luna.glyph.model;

public record GlyphDefinition(
	String name,
	String fileName,
	GlyphType type,
	Integer width,
	Integer height,
	String character
) {
}
