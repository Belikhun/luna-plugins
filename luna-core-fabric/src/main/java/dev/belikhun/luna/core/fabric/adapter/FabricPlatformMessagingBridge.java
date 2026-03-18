package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

public interface FabricPlatformMessagingBridge {
	boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload);

	void setReceiver(FabricPlatformIncomingReceiver receiver);

	void clearReceiver();
}
