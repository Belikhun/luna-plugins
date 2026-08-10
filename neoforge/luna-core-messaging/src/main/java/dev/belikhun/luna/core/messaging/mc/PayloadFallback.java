package dev.belikhun.luna.core.messaging.mc;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Carrying a plugin message over the player's own connection, when the loader
 * offers a way to.
 *
 * The bus prefers this to AMQP for the channels that support it, because a
 * payload riding the connection reaches the proxy without a broker in the path.
 * How a custom payload is registered and sent is the one part that is genuinely
 * per loader - neoforge has a typed payload registry, forge 1.20.1 has raw
 * channels - so the bus talks to this interface and each loader supplies it.
 */
public interface PayloadFallback {
	/** Route inbound payloads to the bus. Called once, when the bus adopts it. */
	void attach(InboundSink sink);

	/** Stop routing. The bus calls this when it closes. */
	void detach();

	/** Whether this channel is one the fallback can carry. */
	boolean supports(PluginMessageChannel channel);

	/** Send, answering false when the payload could not be handed to the connection. */
	boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload);

	/** Where a loader hands the payloads it receives. */
	@FunctionalInterface
	interface InboundSink {
		void accept(ServerPlayer sender, PluginMessageChannel channel, byte[] payload);
	}
}
