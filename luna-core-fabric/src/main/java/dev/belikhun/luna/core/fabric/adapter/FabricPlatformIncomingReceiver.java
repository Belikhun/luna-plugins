package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;

@FunctionalInterface
public interface FabricPlatformIncomingReceiver {
	void onIncoming(FabricMessageSource source, PluginMessageChannel channel, byte[] payload);
}
