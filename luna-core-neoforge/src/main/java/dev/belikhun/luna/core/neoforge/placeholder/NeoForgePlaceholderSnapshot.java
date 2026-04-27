package dev.belikhun.luna.core.neoforge.placeholder;

record NeoForgePlaceholderSnapshot(
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
