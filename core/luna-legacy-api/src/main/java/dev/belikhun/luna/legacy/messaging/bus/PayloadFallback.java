package dev.belikhun.luna.legacy.messaging.bus;

import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;

/**
 * Carrying a plugin message over the player's own connection, when the loader
 * offers a way to.
 *
 * The bus prefers this to AMQP for the channels that support it, because a
 * payload riding the connection reaches the proxy without a broker in the path.
 *
 * **Nothing supplies one on 1.12.2.** That protocol caps a channel name at 20
 * characters and every luna channel is `luna:`-namespaced past it, so the
 * fallback cannot carry ours at all; the 1.12.2 backend is AMQP-only and the
 * seam is kept so the trunk stays the same shape as the modern one.
 */
public interface PayloadFallback<P> {
	/** Route inbound payloads to the bus. Called once, when the bus adopts it. */
	void attach(InboundSink<P> sink);

	/** Stop routing. The bus calls this when it closes. */
	void detach();

	/** Whether this channel is one the fallback can carry. */
	boolean supports(PluginMessageChannel channel);

	/** Send, answering false when the payload could not be handed to the connection. */
	boolean send(P target, PluginMessageChannel channel, byte[] payload);

	/** Where a loader hands the payloads it receives. */
	interface InboundSink<P> {
		void accept(P sender, PluginMessageChannel channel, byte[] payload);
	}
}
