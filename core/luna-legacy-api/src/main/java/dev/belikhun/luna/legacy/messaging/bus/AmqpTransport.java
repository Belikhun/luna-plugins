package dev.belikhun.luna.legacy.messaging.bus;

import dev.belikhun.luna.legacy.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;
import dev.belikhun.luna.legacy.messaging.PluginMessageContext;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;

/** How the bus reaches the broker, or does not. */
public interface AmqpTransport<P> {
	void updateConfig(AmqpMessagingConfig config);

	boolean isActive();

	boolean send(P target, PluginMessageChannel channel, byte[] payload);

	void close();

	/** Where an inbound AMQP body is handed back to the bus. */
	interface InboundSink<P> {
		PluginMessageDispatchResult accept(PluginMessageContext<P> context);
	}

	void registerIncoming(PluginMessageChannel channel, InboundSink<P> sink);

	/**
	 * Deliver this channel on the broker's own thread instead of the server's.
	 *
	 * The default is the server thread, because most listeners touch the world. A
	 * channel that only completes a future must opt out, and the reason is not
	 * speed: the thread it would otherwise wait for is routinely the one already
	 * blocked on that very future, so waiting for the tick is waiting for itself.
	 *
	 * A listener registered this way may touch nothing the tick owns, and must
	 * marshal for itself if it needs to.
	 */
	default void deliverOffTick(PluginMessageChannel channel) {
	}

	void unregisterIncoming(PluginMessageChannel channel);

	void registerOutgoing(PluginMessageChannel channel);

	void unregisterOutgoing(PluginMessageChannel channel);
}
