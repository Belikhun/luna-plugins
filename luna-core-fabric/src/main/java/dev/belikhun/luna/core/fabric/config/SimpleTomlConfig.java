package dev.belikhun.luna.core.fabric.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SimpleTomlConfig {
	private final Map<String, String> values;

	private SimpleTomlConfig(Map<String, String> values) {
		this.values = values;
	}

	public static SimpleTomlConfig load(Path path) throws IOException {
		Map<String, String> values = new LinkedHashMap<>();
		String section = "";
		for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			String trimmed = stripComment(rawLine).trim();
			if (trimmed.isEmpty()) {
				continue;
			}

			if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
				section = trimmed.substring(1, trimmed.length() - 1).trim();
				continue;
			}

			int separator = trimmed.indexOf('=');
			if (separator <= 0) {
				continue;
			}

			String key = trimmed.substring(0, separator).trim();
			String value = trimmed.substring(separator + 1).trim();
			String fullKey = section.isBlank() ? key : section + "." + key;
			values.put(normalizeKey(fullKey), value);
		}

		return new SimpleTomlConfig(values);
	}

	public boolean getBoolean(String key, boolean fallback) {
		String value = values.get(normalizeKey(key));
		if (value == null) {
			return fallback;
		}
		return Boolean.parseBoolean(unquote(value));
	}

	public int getInt(String key, int fallback) {
		String value = values.get(normalizeKey(key));
		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(unquote(value));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public String getString(String key, String fallback) {
		String value = values.get(normalizeKey(key));
		if (value == null) {
			return fallback;
		}
		return unquote(value);
	}

	public List<String> getStringList(String key, List<String> fallback) {
		String value = values.get(normalizeKey(key));
		if (value == null) {
			return fallback;
		}

		String trimmed = value.trim();
		if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
			return fallback;
		}

		String content = trimmed.substring(1, trimmed.length() - 1).trim();
		if (content.isEmpty()) {
			return List.of();
		}

		ArrayList<String> items = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < content.length(); i++) {
			char ch = content.charAt(i);
			if (ch == '"') {
				inQuotes = !inQuotes;
				current.append(ch);
				continue;
			}

			if (ch == ',' && !inQuotes) {
				String item = unquote(current.toString().trim());
				if (!item.isEmpty()) {
					items.add(item);
				}
				current.setLength(0);
				continue;
			}

			current.append(ch);
		}

		String item = unquote(current.toString().trim());
		if (!item.isEmpty()) {
			items.add(item);
		}

		return List.copyOf(items);
	}

	private static String stripComment(String line) {
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '"') {
				inQuotes = !inQuotes;
				continue;
			}
			if (ch == '#' && !inQuotes) {
				return line.substring(0, i);
			}
		}
		return line;
	}

	private static String unquote(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static String normalizeKey(String key) {
		return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
	}
}
