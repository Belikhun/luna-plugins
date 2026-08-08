package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.api.heartbeat.SparkMetrics;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSnapshot;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * Spark's own placeholder names, plus {@code %server_time_<pattern>%}.
 *
 * The two share a provider because both are answered without touching the game:
 * spark's readings come from the metrics luna already samples, and the clock
 * comes from the JVM.
 */
final class SparkPlaceholderProvider implements FabricPlaceholderProvider {
	private static final String TIME_PREFIX = "time_";

	@Override
	public Set<String> namespaces() {
		return Set.of("spark", "server");
	}

	@Override
	public String resolve(
		BuiltInFabricPlaceholderService support,
		ServerPlayer player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		PlaceholderSnapshot snapshot
	) {
		if ("server".equals(normalizedNamespace) && normalizedParams.startsWith(TIME_PREFIX)) {
			// the raw params, not the normalized ones: a date pattern is case
			// sensitive, and "HH" is not "hh"
			return support.formatServerTime(rawParams.substring(TIME_PREFIX.length()));
		}

		if (!"spark".equals(normalizedNamespace)) {
			return null;
		}

		if (normalizedParams != null && !normalizedParams.isBlank()) {
			String sparkValue = SparkMetrics.resolveLegacyPlaceholder(normalizedParams);

			if (!sparkValue.isBlank()) {
				return sparkValue;
			}
		}

		return "tickduration_10s".equals(normalizedParams)
			? support.formatSparkTickDuration(snapshot)
			: null;
	}
}
