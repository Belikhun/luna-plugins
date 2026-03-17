package dev.belikhun.luna.core.api.messaging;

public final class CoreHeartbeatMessageChannels {
	public static final PluginMessageChannel REQUEST_IMMEDIATE_PUBLISH = PluginMessageChannel.of("luna:heartbeat_request_publish");

	private CoreHeartbeatMessageChannels() {
	}
}
