package dev.belikhun.luna.legacy.auth;

import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;

public final class AuthChannels {
	public static final PluginMessageChannel AUTH_STATE = PluginMessageChannel.of("luna:auth_state");
	public static final PluginMessageChannel COMMAND_REQUEST = PluginMessageChannel.of("luna:auth_command_request");
	public static final PluginMessageChannel COMMAND_RESPONSE = PluginMessageChannel.of("luna:auth_command_response");
	public static final PluginMessageChannel ADMIN_REQUEST = PluginMessageChannel.of("luna:auth_admin_request");

	private AuthChannels() {
	}
}