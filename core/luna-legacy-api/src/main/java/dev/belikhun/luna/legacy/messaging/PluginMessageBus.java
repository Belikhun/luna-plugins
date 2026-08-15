package dev.belikhun.luna.legacy.messaging;

import java.util.Set;
import java.util.function.Consumer;

public interface PluginMessageBus<SOURCE, TARGET> extends PluginMessenger<Object, SOURCE> {
	enum DefaultOwner {
		INSTANCE
	}

	Object DEFAULT_OWNER = DefaultOwner.INSTANCE;

	default PluginMessageListenerRegistration<Object, SOURCE> registerIncoming(PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler) {
		return registerIncomingPluginChannel(DEFAULT_OWNER, channel, handler);
	}

	/**
	 * Register a listener that must not be made to wait for the server thread.
	 *
	 * For a reply that only completes a future. Such a listener is answered while
	 * its caller is blocking the server thread, so queueing it for that thread
	 * makes it wait for the very caller waiting for it. The handler may then touch
	 * nothing the tick owns, and marshals for itself if it needs to.
	 *
	 * The default ignores the request, because a transport that already delivers on
	 * its own thread has nothing to opt out of.
	 */
	default PluginMessageListenerRegistration<Object, SOURCE> registerIncomingOffTick(PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler) {
		return registerIncoming(channel, handler);
	}

	default void unregisterIncoming(PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler) {
		unregisterIncomingPluginChannel(DEFAULT_OWNER, channel, handler);
	}

	default void unregisterIncoming(PluginMessageChannel channel) {
		unregisterIncomingPluginChannel(DEFAULT_OWNER, channel);
	}

	default void unregisterIncoming() {
		unregisterIncomingPluginChannel(DEFAULT_OWNER);
	}

	default void registerOutgoing(PluginMessageChannel channel) {
		registerOutgoingPluginChannel(DEFAULT_OWNER, channel);
	}

	default void unregisterOutgoing(PluginMessageChannel channel) {
		unregisterOutgoingPluginChannel(DEFAULT_OWNER, channel);
	}

	default void unregisterOutgoing() {
		unregisterOutgoingPluginChannel(DEFAULT_OWNER);
	}

	default Set<PluginMessageChannel> getIncomingChannelsForDefaultOwner() {
		return getIncomingChannels(DEFAULT_OWNER);
	}

	default Set<PluginMessageChannel> getOutgoingChannelsForDefaultOwner() {
		return getOutgoingChannels(DEFAULT_OWNER);
	}

	default boolean isIncomingChannelRegistered(PluginMessageChannel channel) {
		return isIncomingChannelRegistered(DEFAULT_OWNER, channel);
	}

	default boolean isOutgoingChannelRegistered(PluginMessageChannel channel) {
		return isOutgoingChannelRegistered(DEFAULT_OWNER, channel);
	}

	default PluginMessageDispatchResult dispatchIncomingMessage(SOURCE source, PluginMessageContext<SOURCE> context) {
		return dispatchIncomingMessage(source, context.channel(), context.payload());
	}

	boolean send(TARGET target, PluginMessageChannel channel, byte[] payload);

	default boolean send(TARGET target, PluginMessageChannel channel, Consumer<PluginMessageWriter> payloadWriter) {
		PluginMessageWriter writer = PluginMessageWriter.create();
		payloadWriter.accept(writer);
		return send(target, channel, writer.toByteArray());
	}

	void close();
}
