package dev.belikhun.luna.core.api.ui;

import java.util.Locale;

/**
 * Common presets for progress-bar based metrics.
 *
 * Presets return {@link LunaProgressBar} so callers can continue composing.
 */
public final class LunaProgressBarPresets {
	private static final int DEFAULT_WIDTH = 25;

	private LunaProgressBarPresets() {
	}

	public static LunaProgressBar percent(String label, double percent) {
		double clamped = clampPercent(percent);
		return percent(label, clamped, percentText(clamped));
	}

	public static LunaProgressBar percent(String label, double percent, String valueText) {
		double clamped = clampPercent(percent);
		return metric(label, 0D, 100D, clamped, valueText)
			.filledColor(higherIsBetterColor(clamped));
	}

	public static LunaProgressBar percentGradient(String label, double percent, String gradientStartColor, String gradientEndColor) {
		double clamped = clampPercent(percent);
		return metric(label, 0D, 100D, clamped, percentText(clamped))
			.filledGradient(gradientStartColor, gradientEndColor);
	}

	public static LunaProgressBar percentGradient(String label, double percent, String valueText, String gradientStartColor, String gradientEndColor) {
		double clamped = clampPercent(percent);
		return metric(label, 0D, 100D, clamped, valueText)
			.filledGradient(gradientStartColor, gradientEndColor);
	}

	public static LunaProgressBar items(String label, long current, long max) {
		long safeCurrent = Math.max(0L, current);
		long safeMax = Math.max(0L, max);
		double percent = safeMax <= 0L ? 0D : ((double) safeCurrent * 100D) / (double) safeMax;
		return metric(label, 0D, safeMax, safeCurrent, safeCurrent + "/" + safeMax)
			.filledColor(higherIsBetterColor(percent));
	}

	public static LunaProgressBar tps(String label, double tps) {
		double safeTps = Math.max(0D, tps);
		return metric(label, 0D, 20D, safeTps, tpsText(safeTps))
			.filledGradient(LunaPalette.DANGER_500, LunaPalette.WARNING_300, LunaPalette.SUCCESS_500)
			.valueColorFromFilledGradient(true)
			.allLeft();
	}

	public static LunaProgressBar cpu(String label, double cpuPercent) {
		double percent = clampPercent(cpuPercent);
		return metric(label, 0D, 100D, percent, percentText(percent))
			.filledGradient(LunaPalette.SUCCESS_500, LunaPalette.WARNING_300, LunaPalette.DANGER_500)
			.valueColorFromFilledGradient(true);
	}

	public static LunaProgressBar ram(String label, long usedBytes, long maxBytes) {
		long safeUsed = Math.max(0L, usedBytes);
		long safeMax = Math.max(0L, maxBytes);
		double percent = safeMax <= 0L ? 0D : clampPercent((safeUsed * 100D) / safeMax);
		return metric(label, 0D, safeMax, safeUsed, percentText(percent))
			.filledGradient(LunaPalette.SUCCESS_500, LunaPalette.WARNING_300, LunaPalette.DANGER_500)
			.valueColorFromFilledGradient(true);
	}

	public static LunaProgressBar latency(String label, double latencyMs) {
		return latency(label, latencyMs, 250D);
	}

	public static LunaProgressBar latency(String label, double latencyMs, double warningWindowMs) {
		double safeWindow = warningWindowMs <= 0D ? 250D : warningWindowMs;
		double safeLatency = Math.max(0D, latencyMs);
		return metric(label, 0D, safeWindow, safeLatency, latencyText(safeLatency))
			.filledGradient(LunaPalette.DANGER_500, LunaPalette.WARNING_300, LunaPalette.SUCCESS_500)
			.valueColorFromFilledGradient(true);
	}

	private static LunaProgressBar metric(String label, double min, double max, double current, String displayText) {
		return base(label, min, max, current)
			.value(displayText == null ? "" : displayText);
	}

	private static LunaProgressBar base(String label, double min, double max, double current) {
		return LunaProgressBar.compose()
			.label(label)
			.labelColor(LunaPalette.NEUTRAL_300)
			.range(min, max)
			.value(current)
			.width(DEFAULT_WIDTH)
			.glyph("▋")
			.emptyColor(LunaPalette.NEUTRAL_700)
			.frameColor(LunaPalette.NEUTRAL_500)
			.valueColor(LunaPalette.NEUTRAL_50);
	}

	private static String percentText(double percent) {
		return String.format(Locale.US, "%.1f%%", clampPercent(percent));
	}

	private static String tpsText(double tps) {
		return String.format(Locale.US, "%.1f", Math.max(0D, tps));
	}

	private static String latencyText(double latencyMs) {
		return String.format(Locale.US, "%.0fms", Math.max(0D, latencyMs));
	}

	private static String higherIsBetterColor(double percent) {
		double clamped = clampPercent(percent);
		if (clamped >= 80D) {
			return LunaPalette.SUCCESS_500;
		}
		if (clamped >= 60D) {
			return LunaPalette.WARNING_500;
		}
		return LunaPalette.DANGER_500;
	}

	private static double clampPercent(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0D;
		}
		return Math.max(0D, Math.min(100D, value));
	}
}