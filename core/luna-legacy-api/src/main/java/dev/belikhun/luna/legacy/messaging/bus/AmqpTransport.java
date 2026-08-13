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

	void unregisterIncoming(PluginMessageChannel channel);

	void registerOutgoing(PluginMessageChannel channel);

	void unregisterOutgoing(PluginMessageChannel channel);
}
