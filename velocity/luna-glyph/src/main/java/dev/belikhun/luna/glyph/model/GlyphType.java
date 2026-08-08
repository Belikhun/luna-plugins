package dev.belikhun.luna.glyph.model;

public enum GlyphType {
	ICON,
	IMAGE;

	public static GlyphType fromString(String value) {
		if (value == null) {
			return ICON;
		}

		return switch (value.trim().toLowerCase()) {
			case "image" -> IMAGE;
			default -> ICON;
		};
	}
}
