package dev.belikhun.luna.core.api.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

	public static long longValue(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return fallback;
		}

		try {
			return Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static long longValue(Map<?, ?> source, String key, long fallback) {
		if (source == null) {
			return fallback;
		}
		return longValue(source.get(key), fallback);
	}

	public static double doubleValue(Object value, double fallback) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static double doubleValue(Map<?, ?> source, String key, double fallback) {
		if (source == null) {
			return fallback;
		}
		return doubleValue(source.get(key), fallback);
	}

	/**
	 * Every element of a list value rendered as a string, empty when the value is
	 * not a list. A single scalar counts as a one-element list, because a yaml
	 * author writing {@code lore: "one line"} means the same as a list of one.
	 */
	public static List<String> stringList(Object value) {
		if (value == null) {
			return List.of();
		}

		if (!(value instanceof Iterable<?> iterable)) {
			return List.of(String.valueOf(value));
		}

		List<String> output = new ArrayList<>();
		for (Object element : iterable) {
			output.add(element == null ? "" : String.valueOf(element));
		}
		return output;
	}

	public static List<String> stringList(Map<?, ?> source, String key) {
		if (source == null) {
			return List.of();
		}
		return stringList(source.get(key));
	}

	/**
	 * Resolve a dotted path against a tree of nested maps.
	 *
	 * A path segment that hits a leaf stops the walk and answers null rather than
	 * throwing, which is what lets a caller ask for a key the operator never
	 * wrote and take its fallback.
	 */
	public static Object resolve(Map<?, ?> root, String path) {
		if (root == null || path == null || path.isBlank()) {
			return null;
		}

		Object current = root;
		for (String segment : path.trim().split("\\.")) {
			if (segment.isEmpty()) {
				continue;
			}

			if (!(current instanceof Map<?, ?> node)) {
				return null;
			}

			current = node.get(segment);
		}

		return current == root ? null : current;
	}
}
