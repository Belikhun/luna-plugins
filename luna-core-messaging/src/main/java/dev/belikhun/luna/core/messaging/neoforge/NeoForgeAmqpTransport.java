package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

public interface NeoForgeAmqpTransport {
	void updateConfig(AmqpMessagingConfig config);

	boolean isActive();

	boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload);

	void close();
}
