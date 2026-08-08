package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.function.Supplier;

/**
 * Server metrics from spark, where it is installed, and the caller's own
 * readings where it is not.
 *
 * Spark is optional on every platform, and this class must link on a server that
 * does not have it - so it names no spark type at all. Everything that does
 * lives in {@link SparkProbe}, reached only when {@link #available()} says the
 * API is really there; see that class for why a try/catch is not enough.
 */
public final class SparkMetrics {
	private static final boolean AVAILABLE = probeAvailability();

	private static volatile boolean sparkProbeWarned;

	private SparkMetrics() {
	}

	private static boolean probeAvailability() {
		try {
			Class.forName("me.lucko.spark.api.SparkProvider", false, SparkMetrics.class.getClassLoader());
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** Whether spark is installed and its metrics can be read. */
	public static boolean available() {
		return AVAILABLE;
	}

	/** One of spark's legacy-formatted placeholders, or "" when unavailable. */
	public static String resolveLegacyPlaceholder(String placeholder) {
		if (placeholder == null || placeholder.isBlank() || !AVAILABLE) {
			return "";
		}

		return SparkProbe.placeholder(placeholder);
	}

	/**
	 * Spark's reading of tick rate and CPU, falling back to the suppliers the
	 * caller provides when spark is absent or not yet ready.
	 */
	public static Snapshot collect(
		LunaLogger logger,
		Supplier<Double> fallbackTpsSupplier,
		Supplier<Double> fallbackSystemCpuSupplier,
		Supplier<Double> fallbackProcessCpuSupplier
	) {
		if (AVAILABLE) {
			Snapshot snapshot = SparkProbe.read(logger, fallbackTpsSupplier);

			if (snapshot != null) {
				return snapshot;
			}
		}

		return new Snapshot(
			safeValue(fallbackTpsSupplier, 20D),
			safeValue(fallbackSystemCpuSupplier, 0D),
			safeValue(fallbackProcessCpuSupplier, 0D),
			""
		);
	}

	static void warnOnce(LunaLogger logger, String message) {
		if (sparkProbeWarned || logger == null) {
			return;
		}

		sparkProbeWarned = true;
		logger.warn(message);
	}

	static double safeValue(Supplier<Double> supplier, double fallback) {
		if (supplier == null) {
			return fallback;
		}

		try {
			Double value = supplier.get();
			return value == null ? fallback : value;
		} catch (Throwable ignored) {
			return fallback;
		}
	}

	static double normalizeCpuPercent(double raw) {
		if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0D) {
			return 0D;
		}

		double percent = raw <= 1D ? raw * 100D : raw;
		return Math.max(0D, Math.min(100D, percent));
	}

	public record Snapshot(double tps, double systemCpuUsagePercent, double processCpuUsagePercent, String sparkTickDuration10Sec) {
	}
}
