package dev.belikhun.luna.core.api.messaging;

import java.util.function.Consumer;

public interface PluginMessageBus<SOURCE, TARGET> {
	void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler);

	void unregisterIncoming(PluginMessageChannel channel);

	void registerOutgoing(PluginMessageChannel channel);

	void unregisterOutgoing(PluginMessageChannel channel);

	boolean send(TARGET target, PluginMessageChannel channel, byte[] payload);

	default boolean send(TARGET target, PluginMessageChannel channel, Consumer<PluginMessageWriter> payloadWriter) {
		PluginMessageWriter writer = PluginMessageWriter.create();
		payloadWriter.accept(writer);
		return send(target, channel, writer.toByteArray());
	}

	void close();
}
