package dev.belikhun.luna.core.messaging.fabric.amqp;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The broker route a plugin message takes when the player connection cannot
 * carry it, and the route the proxy uses to reach a player who is not on this
 * backend.
 *
 * There is no per-channel registration here on purpose: one queue per backend
 * carries every channel, and the envelope names the channel it belongs to.
 */
public interface FabricAmqpTransport {
	void updateConfig(AmqpMessagingConfig config);

	boolean isActive();

	boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload);

	void close();
}
