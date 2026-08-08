package dev.belikhun.luna.core.messaging.fabric.amqp;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a transport hands a message it has just received.
 *
 * The bus implements this; the transports take it as a constructor argument
 * rather than holding the bus, so a transport can be built and tested without
 * one.
 */
@FunctionalInterface
public interface IncomingMessageSink {
	PluginMessageDispatchResult dispatch(ServerPlayer source, PluginMessageChannel channel, byte[] payload);
}
