package dev.belikhun.luna.core.fabric.messaging;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;

public interface FabricMessagingTransport {
	void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<FabricMessageSource> handler);

	void unregisterIncoming(PluginMessageChannel channel);

	void registerOutgoing(PluginMessageChannel channel);

	void unregisterOutgoing(PluginMessageChannel channel);

	boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload);

	void close();
}
