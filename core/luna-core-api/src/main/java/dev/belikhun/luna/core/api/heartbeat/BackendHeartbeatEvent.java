package dev.belikhun.luna.core.api.heartbeat;

public record BackendHeartbeatEvent(
	BackendHeartbeatEventType type,
	BackendServerStatus current,
	BackendServerStatus previous,
	long atEpochMillis
) {
}
