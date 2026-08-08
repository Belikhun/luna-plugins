package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.event.LunaEventListener;

@FunctionalInterface
public interface BackendHeartbeatListener extends LunaEventListener<BackendHeartbeatEvent> {
	void onBackendHeartbeatEvent(BackendHeartbeatEvent event);

	@Override
	default void onEvent(BackendHeartbeatEvent event) {
		onBackendHeartbeatEvent(event);
	}
}
