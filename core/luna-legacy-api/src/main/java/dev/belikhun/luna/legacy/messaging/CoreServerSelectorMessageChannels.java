package dev.belikhun.luna.legacy.messaging;

public final class CoreServerSelectorMessageChannels {
	public static final PluginMessageChannel OPEN_MENU = PluginMessageChannel.of("luna:server_selector_open");
	public static final PluginMessageChannel CONNECT_REQUEST = PluginMessageChannel.of("luna:server_selector_connect");

	private CoreServerSelectorMessageChannels() {
	}
}
