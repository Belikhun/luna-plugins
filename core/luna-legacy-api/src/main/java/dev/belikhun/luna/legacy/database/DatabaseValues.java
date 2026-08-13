package dev.belikhun.luna.legacy.database;

import dev.belikhun.luna.legacy.string.Strings;

import java.util.UUID;

public final class DatabaseValues {
	private DatabaseValues() {
	}

	public static String string(Object value, String fallback) {
		if (value == null) {
			return fallback;
		}

		return String.valueOf(value);
	}

	public static String nonBlankOrNull(Object value) {
		if (value == null) {
			return null;
		}

		String string = String.valueOf(value);
		return Strings.isBlank(string) ? null : string;
	}

	public static int intValue(Object value, int fallback) {
		if (value instanceof Number) {
			Number number = (Number) value;

			return number.intValue();
		}

		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	public static long longValue(Object value, long fallback) {
		if (value instanceof Number) {
			Number number = (Number) value;

			return number.longValue();
		}

		if (value == null) {
			return fallback;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	public static double doubleValue(Object value, double fallback) {
		if (value instanceof Number) {
			Number number = (Number) value;

			return number.doubleValue();
		}

		if (value == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	public static UUID uuidOrNull(Object value) {
		String string = nonBlankOrNull(value);
		if (string == null) {
			return null;
		}

		try {
			return UUID.fromString(string);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
