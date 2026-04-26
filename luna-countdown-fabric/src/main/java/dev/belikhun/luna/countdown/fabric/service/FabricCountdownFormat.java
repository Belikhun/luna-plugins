package dev.belikhun.luna.countdown.fabric.service;

import dev.belikhun.luna.core.api.ui.LunaPalette;

import java.util.Locale;

public final class FabricCountdownFormat {

	private FabricCountdownFormat() {
	}

	public static int parseTime(String input) {
		try {
			if (input == null || input.isBlank()) {
				return -1;
			}

			String value = input.trim().toLowerCase(Locale.ROOT);
			if (value.endsWith("d")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 86400;
			}
			if (value.endsWith("h")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 3600;
			}
			if (value.endsWith("m")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 60;
			}
			if (value.endsWith("s")) {
				return Integer.parseInt(value.substring(0, value.length() - 1));
			}
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	public static String readableTime(double seconds) {
		if (seconds / 3600d > 1) {
			return String.format("<color:%s>%.0fh %.2fm</color>", LunaPalette.AMBER_300, Math.floor(seconds / 3600d), (seconds % 3600d) / 60d);
		}
		if (seconds > 300d) {
			return String.format("<color:%s>%.0fm %.0fs</color>", LunaPalette.TEAL_300, Math.floor(seconds / 60d), (seconds % 60d));
		}
		return String.format("<color:%s>%.1fs</color>", LunaPalette.SKY_300, seconds);
	}

	public static String readablePlainTime(double seconds) {
		double safeSeconds = Math.max(0d, seconds);
		if (safeSeconds / 3600d > 1d) {
			return String.format(Locale.ROOT, "%.0fh %.2fm", Math.floor(safeSeconds / 3600d), (safeSeconds % 3600d) / 60d);
		}
		if (safeSeconds > 300d) {
			return String.format(Locale.ROOT, "%.0fm %.0fs", Math.floor(safeSeconds / 60d), (safeSeconds % 60d));
		}
		return String.format(Locale.ROOT, "%.1fs", safeSeconds);
	}

	public static String stripMiniMessage(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		return text
			.replace("<br>", "\n")
			.replace("<newline>", "\n")
			.replaceAll("<[^>]+>", "")
			.replaceAll("\\s+", " ")
			.trim();
	}
}
