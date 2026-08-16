package dev.belikhun.luna.legacy.placeholder;

/** How a value reaches a consumer that would otherwise re-read its percent signs. */
public final class PlaceholderEscaping {
	private static final String PERCENT_ESCAPE = "％";

	private PlaceholderEscaping() {
	}

	/**
	 * Replaces every percent sign so a resolved value cannot be mistaken for
	 * another placeholder by whatever expands the text next.
	 *
	 * @param value the resolved value, which may be null
	 * @return the value with its percent signs escaped, empty when null
	 */
	public static String escapePercents(String value) {
		return value == null ? "" : value.replace("%", PERCENT_ESCAPE);
	}
}
