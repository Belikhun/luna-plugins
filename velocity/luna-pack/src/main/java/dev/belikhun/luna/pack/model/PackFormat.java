package dev.belikhun.luna.pack.model;

import java.util.List;

/**
 * One resource-pack format number. Formats up to 64 are plain integers
 * (minor is always 0); 1.21.9 renumbered them into major.minor pairs
 * carried by min_format/max_format, and both shapes compare the same way:
 * by major, then by minor.
 */
public record PackFormat(int major, int minor) implements Comparable<PackFormat> {
	/** The last format the legacy pack_format/supported_formats fields can describe. */
	public static final PackFormat LEGACY_CEILING = new PackFormat(64, 0);

	@Override
	public int compareTo(PackFormat other) {
		if (major != other.major) {
			return Integer.compare(major, other.major);
		}
		return Integer.compare(minor, other.minor);
	}

	public boolean isAfter(PackFormat other) {
		return compareTo(other) > 0;
	}

	public String render() {
		if (minor == 0) {
			return String.valueOf(major);
		}
		return major + "." + minor;
	}

	/**
	 * Parse the shapes a format number takes in pack.mcmeta and in our config:
	 * a plain integer, a [major, minor] pair, or a "major.minor"/"major" string.
	 * Returns null for anything else.
	 */
	public static PackFormat parse(Object value) {
		if (value instanceof Number number) {
			return new PackFormat(number.intValue(), 0);
		}

		if (value instanceof List<?> list && list.size() >= 2
			&& list.get(0) instanceof Number first && list.get(1) instanceof Number second) {
			return new PackFormat(first.intValue(), second.intValue());
		}

		if (value instanceof String text) {
			return parseText(text);
		}

		return null;
	}

	private static PackFormat parseText(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return null;
		}

		int dot = trimmed.indexOf('.');
		try {
			if (dot < 0) {
				return new PackFormat(Integer.parseInt(trimmed), 0);
			}
			return new PackFormat(
				Integer.parseInt(trimmed.substring(0, dot)),
				Integer.parseInt(trimmed.substring(dot + 1))
			);
		} catch (NumberFormatException exception) {
			return null;
		}
	}
}
