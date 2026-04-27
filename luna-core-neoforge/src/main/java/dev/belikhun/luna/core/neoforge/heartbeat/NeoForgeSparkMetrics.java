package dev.belikhun.luna.core.neoforge.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;

import java.util.function.Supplier;

public final class NeoForgeSparkMetrics {
	private static volatile boolean sparkProbeWarned;

	private NeoForgeSparkMetrics() {
	}

	public static Snapshot collect(
		LunaLogger logger,
		Supplier<Double> fallbackTpsSupplier,
		Supplier<Double> fallbackSystemCpuSupplier,
		Supplier<Double> fallbackProcessCpuSupplier
	) {
		try {
			Spark spark = SparkProvider.get();
			double tps = safeValue(fallbackTpsSupplier, 20D);
			if (spark.tps() != null) {
				double sparkTps = spark.tps().poll(TicksPerSecond.SECONDS_10);
				if (sparkTps > 0D) {
					tps = sparkTps;
				}
			}

			double systemCpuPercent = normalizeCpuPercent(spark.cpuSystem().poll(CpuUsage.MINUTES_1));
			double processCpuPercent = normalizeCpuPercent(spark.cpuProcess().poll(CpuUsage.MINUTES_1));
			return new Snapshot(tps, systemCpuPercent, processCpuPercent);
		} catch (IllegalStateException exception) {
			warnOnce(logger, "Spark chưa sẵn sàng, dùng fallback nội bộ cho metrics: " + exception.getMessage());
		} catch (Throwable throwable) {
			warnOnce(logger, "Không thể đọc metrics từ Spark API, dùng fallback nội bộ: " + throwable.getMessage());
		}

		return new Snapshot(
			safeValue(fallbackTpsSupplier, 20D),
			safeValue(fallbackSystemCpuSupplier, 0D),
			safeValue(fallbackProcessCpuSupplier, 0D)
		);
	}

	private static void warnOnce(LunaLogger logger, String message) {
		if (sparkProbeWarned || logger == null) {
			return;
		}

		sparkProbeWarned = true;
		logger.warn(message);
	}

	private static double safeValue(Supplier<Double> supplier, double fallback) {
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

	private static double normalizeCpuPercent(double raw) {
		if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0D) {
			return 0D;
		}

		double percent = raw <= 1D ? raw * 100D : raw;
		return Math.max(0D, Math.min(100D, percent));
	}

	public record Snapshot(double tps, double systemCpuUsagePercent, double processCpuUsagePercent) {
	}
}
