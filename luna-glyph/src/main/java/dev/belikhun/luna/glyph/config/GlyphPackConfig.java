package dev.belikhun.luna.glyph.config;

import java.util.List;

public record GlyphPackConfig(
	String name,
	String description,
	String filename,
	String namespace,
	int priority,
	boolean required,
	boolean enabled,
	int startCodepoint,
	List<String> servers
) {
}
