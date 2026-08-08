package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;

import java.util.function.Supplier;

/**
 * Every reference to spark's API, kept in a class of its own.
 *
 * This is the whole reason the class exists. Verification is a *class*-level
 * phase, and it loads the types a method's bytecode compares - so one method
 * naming {@code StatisticWindow.TicksPerSecond} makes the entire class fail to
 * link on a server without spark, no matter what guards its body contains, and
 * whatever called it dies with a NoClassDefFoundError. {@link SparkMetrics}
 * holds the availability flag and never names a spark type, so it always links;
 * this class is only ever reached through that flag, so on a server without
 * spark it is never loaded at all.
 *
 * Nothing outside {@code SparkMetrics} may call into here.
 */
final class SparkProbe {
	private SparkProbe() {
	}

	/** Spark's own reading, or null when it cannot give one. */
	static SparkMetrics.Snapshot read(
		LunaLogger logger,
		Supplier<Double> fallbackTpsSupplier
	) {
		try {
			Spark spark = SparkProvider.get();
			double tps = SparkMetrics.safeValue(fallbackTpsSupplier, 20D);
			if (spark.tps() != null) {
				double sparkTps = spark.tps().poll(TicksPerSecond.SECONDS_10);
				if (sparkTps > 0D) {
					tps = sparkTps;
				}
			}

			double systemCpuPercent = SparkMetrics.normalizeCpuPercent(spark.cpuSystem().poll(CpuUsage.MINUTES_1));
			double processCpuPercent = SparkMetrics.normalizeCpuPercent(spark.cpuProcess().poll(CpuUsage.MINUTES_1));
			return new SparkMetrics.Snapshot(tps, systemCpuPercent, processCpuPercent, placeholder("tickduration_10s"));
		} catch (IllegalStateException exception) {
			SparkMetrics.warnOnce(logger, "Spark chưa sẵn sàng, dùng fallback nội bộ cho metrics: " + exception.getMessage());
		} catch (Throwable throwable) {
			SparkMetrics.warnOnce(logger, "Không thể đọc metrics từ Spark API, dùng fallback nội bộ: " + throwable.getMessage());
		}

		return null;
	}

	/** One of spark's legacy-formatted placeholders, or "" when unreadable. */
	static String placeholder(String placeholder) {
		try {
			String value = SparkProvider.get().placeholders().resolveLegacyFormatting(placeholder);
			return value == null ? "" : value;
		} catch (Throwable ignored) {
			return "";
		}
	}
}
