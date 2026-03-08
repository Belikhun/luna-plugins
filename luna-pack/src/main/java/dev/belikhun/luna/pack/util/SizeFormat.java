package dev.belikhun.luna.pack.util;

import dev.belikhun.luna.core.api.string.Formatters;

public final class SizeFormat {
	private SizeFormat() {
	}

	public static String humanBytes(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}

		double value = bytes;
		String[] units = {"KB", "MB", "GB", "TB"};
		int index = -1;
		while (value >= 1024.0 && index < units.length - 1) {
			value /= 1024.0;
			index++;
		}

		if (index < 0) {
			return bytes + " B";
		}

		return Formatters.number(value) + " " + units[index];
	}
}
