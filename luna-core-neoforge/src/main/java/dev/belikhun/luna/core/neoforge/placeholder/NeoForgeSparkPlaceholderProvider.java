package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeSparkMetrics;

import java.util.Set;

final class NeoForgeSparkPlaceholderProvider implements NeoForgePlaceholderProvider {
	@Override
	public Set<String> namespaces() {
		return Set.of("spark", "server");
	}

	@Override
	public String resolve(
		BuiltInNeoForgePlaceholderService support,
		net.minecraft.server.level.ServerPlayer player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		NeoForgePlaceholderSnapshot snapshot
	) {
		if ("server".equals(normalizedNamespace) && normalizedParams.startsWith("time_")) {
			return support.formatServerTime(rawParams.substring("time_".length()));
		}

		if (!"spark".equals(normalizedNamespace)) {
			return null;
		}

		if (normalizedParams != null && !normalizedParams.isBlank()) {
			String sparkValue = NeoForgeSparkMetrics.resolveLegacyPlaceholder(
				normalizedParams
			);
			if (!sparkValue.isBlank()) {
				return sparkValue;
			}
		}

		return "tickduration_10s".equals(normalizedParams)
			? support.formatSparkTickDuration(snapshot)
			: null;
	}
}
