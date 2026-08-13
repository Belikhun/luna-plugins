package dev.belikhun.luna.legacy.string;

/**
 * The String methods this line does not have.
 *
 * `isBlank` is Java 11 and the modern api reaches for it in 42 files, which makes it
 * the single most common reason a file does not compile here. Rewriting each call site
 * by hand would be 42 chances to write `isEmpty` instead and quietly change the meaning
 * for a string of spaces, so they all come here.
 */
public final class Strings {
	private Strings() {
	}

	/** Null, empty, or nothing but whitespace. */
	public static boolean isBlank(String value) {
		if (value == null || value.isEmpty()) {
			return true;
		}

		for (int index = 0; index < value.length(); index += 1) {
			if (!Character.isWhitespace(value.charAt(index))) {
				return false;
			}
		}

		return true;
	}

	/** The inverse, spelled out because `!isBlank(x)` reads badly inside a longer test. */
	public static boolean hasText(String value) {
		return !isBlank(value);
	}

	/** `value`, or `fallback` when it has no text. */
	public static String orElse(String value, String fallback) {
		return isBlank(value) ? fallback : value;
	}

	/** Trimmed, treating null as empty. */
	public static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	/** `String.repeat`, which is Java 11. */
	public static String repeat(String value, int count) {
		if (value == null || count <= 0) {
			return "";
		}

		StringBuilder builder = new StringBuilder(value.length() * count);

		for (int index = 0; index < count; index += 1) {
			builder.append(value);
		}

		return builder.toString();
	}
}
