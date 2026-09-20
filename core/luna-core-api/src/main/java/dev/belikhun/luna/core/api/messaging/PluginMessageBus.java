package dev.belikhun.luna.core.api.messaging;

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

	/**
	 * Let a channel's inbound frames be handled on the thread they arrive on.
	 *
	 * Handlers normally run on the server thread, which is right for anything
	 * that touches the world and fatal for a request/reply channel whose caller
	 * is *blocking* the server thread waiting for the reply: the reply queues
	 * behind the wait and the wait times out. A channel that opts in promises
	 * its handler is thread-safe and touches no game state. Transports that have
	 * no thread of their own (a plugin message arrives on the server thread by
	 * construction) ignore this.
	 */
	default void allowAsyncDelivery(PluginMessageChannel channel) {
	}

	default boolean send(TARGET target, PluginMessageChannel channel, Consumer<PluginMessageWriter> payloadWriter) {
		PluginMessageWriter writer = PluginMessageWriter.create();
		payloadWriter.accept(writer);
		return send(target, channel, writer.toByteArray());
	}

	void close();
}
