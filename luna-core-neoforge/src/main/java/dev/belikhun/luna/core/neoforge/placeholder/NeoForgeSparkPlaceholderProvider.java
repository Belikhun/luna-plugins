package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeSparkMetrics;

final class NeoForgeSparkPlaceholderProvider implements NeoForgePlaceholderProvider {
	@Override
	public String resolveNativeValue(
		BuiltInNeoForgePlaceholderService support,
		net.minecraft.server.level.ServerPlayer player,
		String rawIdentifier,
		String normalizedIdentifier,
		NeoForgePlaceholderSnapshot snapshot
	) {
		if (normalizedIdentifier.startsWith(BuiltInNeoForgePlaceholderService.SERVER_TIME_PREFIX)) {
			return support.formatServerTime(rawIdentifier.substring(BuiltInNeoForgePlaceholderService.SERVER_TIME_PREFIX.length()));
		}

		if (normalizedIdentifier.startsWith(BuiltInNeoForgePlaceholderService.SPARK_PREFIX)) {
			String sparkValue = NeoForgeSparkMetrics.resolveLegacyPlaceholder(
				normalizedIdentifier.substring(BuiltInNeoForgePlaceholderService.SPARK_PREFIX.length())
			);
			if (!sparkValue.isBlank()) {
				return sparkValue;
			}
		}

		return BuiltInNeoForgePlaceholderService.SPARK_TICK_DURATION_10S.equals(normalizedIdentifier)
			? support.formatSparkTickDuration(snapshot)
			: null;
	}
}
