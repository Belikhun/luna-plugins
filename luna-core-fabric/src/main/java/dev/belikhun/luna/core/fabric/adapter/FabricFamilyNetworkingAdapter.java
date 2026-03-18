package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

public interface FabricFamilyNetworkingAdapter {

	FabricVersionFamily family();

	String adapterId();

	void initialize();

	void shutdown();

	void bindPlatformBridge(FabricPlatformMessagingBridge bridge);

	void setIncomingMessageConsumer(FabricPlatformIncomingReceiver receiver);

	boolean sendPluginMessage(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload);
}
