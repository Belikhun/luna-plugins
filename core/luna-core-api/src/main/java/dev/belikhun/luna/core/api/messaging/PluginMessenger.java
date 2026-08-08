package dev.belikhun.luna.core.api.messaging;

import java.util.Objects;
import java.util.Set;

public interface PluginMessenger<OWNER, SOURCE> {
	default boolean isReservedChannel(PluginMessageChannel channel) {
		return Objects.requireNonNull(channel, "channel").isReserved();
	}

	void registerOutgoingPluginChannel(OWNER owner, PluginMessageChannel channel);

	void unregisterOutgoingPluginChannel(OWNER owner, PluginMessageChannel channel);

	void unregisterOutgoingPluginChannel(OWNER owner);

	PluginMessageListenerRegistration<OWNER, SOURCE> registerIncomingPluginChannel(OWNER owner, PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler);

	void unregisterIncomingPluginChannel(OWNER owner, PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler);

	void unregisterIncomingPluginChannel(OWNER owner, PluginMessageChannel channel);

	void unregisterIncomingPluginChannel(OWNER owner);

	Set<PluginMessageChannel> getOutgoingChannels();

	Set<PluginMessageChannel> getOutgoingChannels(OWNER owner);

	Set<PluginMessageChannel> getIncomingChannels();

	Set<PluginMessageChannel> getIncomingChannels(OWNER owner);

	Set<PluginMessageListenerRegistration<OWNER, SOURCE>> getIncomingChannelRegistrations(OWNER owner);

	Set<PluginMessageListenerRegistration<OWNER, SOURCE>> getIncomingChannelRegistrations(PluginMessageChannel channel);

	Set<PluginMessageListenerRegistration<OWNER, SOURCE>> getIncomingChannelRegistrations(OWNER owner, PluginMessageChannel channel);

	boolean isRegistrationValid(PluginMessageListenerRegistration<OWNER, SOURCE> registration);

	boolean isIncomingChannelRegistered(OWNER owner, PluginMessageChannel channel);

	boolean isOutgoingChannelRegistered(OWNER owner, PluginMessageChannel channel);

	PluginMessageDispatchResult dispatchIncomingMessage(SOURCE source, PluginMessageChannel channel, byte[] payload);

	void clear();
}
