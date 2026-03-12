package dev.belikhun.luna.core.api.heartbeat;

public record BackendServerStatus(
	String serverName,
	boolean online,
	long lastHeartbeatEpochMillis,
	BackendHeartbeatStats stats
) {
}
