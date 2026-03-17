package dev.belikhun.luna.core.api.heartbeat;

public record BackendServerStatus(
	String serverName,
	String serverDisplay,
	String serverAccentColor,
	boolean online,
	long lastHeartbeatEpochMillis,
	BackendHeartbeatStats stats
) {
	public BackendMetadata metadata() {
		return new BackendMetadata(serverName, serverDisplay, serverAccentColor).sanitize();
	}
}
