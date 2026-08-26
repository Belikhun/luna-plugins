package dev.belikhun.luna.core.api.heartbeat;

import java.util.Locale;

/**
 * Spark's rendering of tick-duration readings, reproduced exactly, so a value
 * luna composes itself is indistinguishable from one spark formats.
 *
 * Measured against spark's StatisticFormatter: each value is "%.1f" colored by
 * the reading (green under 40 ms, yellow from 40, red from 50), and values join
 * with a gray "/". Spark serializes through legacy *section* codes, so this
 * emits the same paragraph-sign codes rather than ampersands.
 */
public final class TickDurationFormat {
	private static final double YELLOW_FROM_MILLIS = 40D;
	private static final double RED_FROM_MILLIS = 50D;

	private TickDurationFormat() {
	}

	/**
	 * A min/median/95th percentile/max window, colored and joined as spark's
	 * tickduration placeholders render it.
	 *
	 * @param min the window's fastest tick, in milliseconds
	 * @param median the window's median tick
	 * @param percentile95th the window's 95th percentile tick
	 * @param max the window's slowest tick
	 * @return the legacy-formatted spread, e.g. {@code §a3.7§7/§a8.0§7/§e47.1§7/§c105.3}
	 */
	public static String spread(double min, double median, double percentile95th, double max) {
		return value(min) + "§7/" + value(median) + "§7/" + value(percentile95th) + "§7/" + value(max);
	}

	/**
	 * One tick duration, "%.1f" behind spark's color for that reading.
	 *
	 * @param duration the tick duration in milliseconds
	 * @return the colored legacy-formatted value
	 */
	public static String value(double duration) {
		double clamped = Math.max(0D, duration);

		String color;
		if (clamped >= RED_FROM_MILLIS) {
			color = "§c";
		} else if (clamped >= YELLOW_FROM_MILLIS) {
			color = "§e";
		} else {
			color = "§a";
		}

		return color + String.format(Locale.US, "%.1f", clamped);
	}
}
