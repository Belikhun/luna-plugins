package dev.belikhun.luna.core.api.heartbeat;

public interface BackendHeartbeatEventEmitter {
	void addHeartbeatListener(BackendHeartbeatListener listener);

	void removeHeartbeatListener(BackendHeartbeatListener listener);
}
