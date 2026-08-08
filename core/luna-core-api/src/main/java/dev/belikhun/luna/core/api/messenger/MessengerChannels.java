package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;

public final class MessengerChannels {
	public static final PluginMessageChannel COMMAND = PluginMessageChannel.of("luna:messenger_command");
	public static final PluginMessageChannel RESULT = PluginMessageChannel.of("luna:messenger_result");
	public static final PluginMessageChannel SYNC = PluginMessageChannel.of("luna:messenger_sync");
	public static final PluginMessageChannel PRESENCE = PluginMessageChannel.of("luna:messenger_presence");

	private MessengerChannels() {
	}
}
