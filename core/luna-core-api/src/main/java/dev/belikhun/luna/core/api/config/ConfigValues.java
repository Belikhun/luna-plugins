package dev.belikhun.luna.core.api.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ConfigValues {
	private ConfigValues() {
	}

	public static Map<String, Object> map(Object value) {
		if (!(value instanceof Map<?, ?> nested)) {
			return Map.of();
		}

		Map<String, Object> output = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : nested.entrySet()) {
			output.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return output;
	}

	public static Map<String, Object> map(Map<?, ?> source, String key) {
		if (source == null) {
			return Map.of();
		}
		return map(source.get(key));
	}

	public static String string(Object value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? fallback : text;
	}

	public static String stringPreserveWhitespace(Object value, String fallback) {
		if (value == null) {
			return fallback;
		}

		String text = String.valueOf(value);
		return text.isEmpty() ? fallback : text;
	}

	public static String string(Map<?, ?> source, String key, String fallback) {
		if (source == null) {
			return fallback;
		}
		return string(source.get(key), fallback);
	}

	public static int intValue(Object value, int fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static int intValue(Map<?, ?> source, String key, int fallback) {
		if (source == null) {
			return fallback;
		}
		return intValue(source.get(key), fallback);
	}

	public static Integer integerValue(Object value, Integer fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static boolean booleanValue(Object value, boolean fallback) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value == null) {
			return fallback;
		}

		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		if (text.equals("true") || text.equals("yes") || text.equals("1")) {
			return true;
		}
		if (text.equals("false") || text.equals("no") || text.equals("0")) {
			return false;
		}
		return fallback;
	}

	public static boolean booleanValue(Map<?, ?> source, String key, boolean fallback) {
		if (source == null) {
			return fallback;
		}
		return booleanValue(source.get(key), fallback);
	}
}
