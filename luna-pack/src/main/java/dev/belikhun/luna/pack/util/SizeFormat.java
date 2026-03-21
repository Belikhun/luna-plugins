package dev.belikhun.luna.pack.util;

import dev.belikhun.luna.core.api.string.Formatters;

public final class SizeFormat {
	private SizeFormat() {
	}

	public static String humanBytes(long bytes) {
		return Formatters.bytes(bytes);
	}
}
