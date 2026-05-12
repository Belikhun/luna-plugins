package dev.belikhun.luna.core.neoforge.placeholder;

final class NeoForgePlaceholderEscaping {
	private NeoForgePlaceholderEscaping() {
	}

	static String escapePercents(String value) {
		return value == null ? "" : value.replace("%", ":percent:");
	}
}
