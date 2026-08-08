package dev.belikhun.luna.core.api.placeholder;

/** The server statistics one resolution round sees, fixed for its duration. */
public record PlaceholderSnapshot(
	double currentTps,
	double currentTickDurationMillis,
	String sparkTickDuration10Sec,
	int playerPingMillis,
	long uptimeMillis,
	double systemCpuPercent,
	double processCpuPercent,
	long ramUsedBytes,
	long ramMaxBytes,
	int totalEntities,
	int totalLivingEntities,
	int totalChunks
) {
}
