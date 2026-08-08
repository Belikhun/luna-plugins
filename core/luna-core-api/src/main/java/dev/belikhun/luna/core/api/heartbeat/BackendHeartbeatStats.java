package dev.belikhun.luna.core.api.heartbeat;

public record BackendHeartbeatStats(
	String software,
	String version,
	int serverPort,
	long uptimeMillis,
	double tps,
	int onlinePlayers,
	int maxPlayers,
	String motd,
	boolean whitelistEnabled,
	double systemCpuUsagePercent,
	double processCpuUsagePercent,
	long ramUsedBytes,
	long ramFreeBytes,
	long ramMaxBytes,
	long heartbeatLatencyMillis
) {
}
