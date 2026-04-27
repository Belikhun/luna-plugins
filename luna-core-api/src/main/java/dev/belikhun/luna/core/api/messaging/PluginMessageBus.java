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

	default boolean send(TARGET target, PluginMessageChannel channel, Consumer<PluginMessageWriter> payloadWriter) {
		PluginMessageWriter writer = PluginMessageWriter.create();
		payloadWriter.accept(writer);
		return send(target, channel, writer.toByteArray());
	}

	void close();
}
