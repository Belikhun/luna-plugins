package dev.belikhun.luna.smp.packprotect.messaging;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;

public final class PackLoadStateChannel {
	public static final PluginMessageChannel CHANNEL = PluginMessageChannel.of("luna:pack_load_state");

	private PackLoadStateChannel() {
	}
}
