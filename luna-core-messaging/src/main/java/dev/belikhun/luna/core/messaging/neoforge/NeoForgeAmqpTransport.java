package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

public interface NeoForgeAmqpTransport {
	void updateConfig(AmqpMessagingConfig config);

	default void registerIncoming(PluginMessageChannel channel, dev.belikhun.luna.core.api.messaging.PluginMessageHandler<ServerPlayer> handler) {
	}

	default void unregisterIncoming(PluginMessageChannel channel) {
	}

	default void registerOutgoing(PluginMessageChannel channel) {
	}

	default void unregisterOutgoing(PluginMessageChannel channel) {
	}

	boolean isActive();

	boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload);

	void close();
}
