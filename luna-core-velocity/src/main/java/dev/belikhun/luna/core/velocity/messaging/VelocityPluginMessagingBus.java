package dev.belikhun.luna.core.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageListenerRegistration;
import dev.belikhun.luna.core.api.messaging.StandardPluginMessenger;

import java.util.Set;

public final class VelocityPluginMessagingBus implements PluginMessageBus<Object, Object> {
	private final VelocityBungeePluginMessagingBus fallbackBus;
	private final VelocityAmqpMessagingTransport amqpTransport;
	private final StandardPluginMessenger<Object, Object> messenger;

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger) {
		this(proxyServer, plugin, logger, false);
	}

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger, boolean loggingEnabled) {
		this.fallbackBus = new VelocityBungeePluginMessagingBus(proxyServer, plugin, logger, loggingEnabled);
		this.amqpTransport = new VelocityAmqpMessagingTransport(proxyServer, plugin, logger, loggingEnabled);
		this.messenger = new StandardPluginMessenger<>((registration, throwable) -> logger.warn(
			"Listener owner=" + registration.getOwner() + " ném lỗi khi xử lý plugin message channel=" + registration.getChannel() + ": " + throwable.getMessage()
		));
	}

	public void updateAmqpConfig(AmqpMessagingConfig config) {
		amqpTransport.updateConfig(config);
	}

	public boolean isAmqpActive() {
		return amqpTransport.isActive();
	}

	@Override
	public PluginMessageListenerRegistration<Object, Object> registerIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<Object> handler) {
		PluginMessageChannel safeChannel = java.util.Objects.requireNonNull(channel, "channel");
		boolean shouldRegisterTransport = messenger.getIncomingChannelRegistrations(safeChannel).isEmpty();
		PluginMessageListenerRegistration<Object, Object> registration = messenger.registerIncomingPluginChannel(owner, safeChannel, handler);
		if (shouldRegisterTransport) {
			fallbackBus.registerIncoming(safeChannel, this::dispatchIncomingContext);
			amqpTransport.registerIncoming(safeChannel, this::dispatchIncomingContext);
		}
		return registration;
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<Object> handler) {
		PluginMessageChannel safeChannel = java.util.Objects.requireNonNull(channel, "channel");
		messenger.unregisterIncomingPluginChannel(owner, safeChannel, handler);
		if (messenger.getIncomingChannelRegistrations(safeChannel).isEmpty()) {
			fallbackBus.unregisterIncoming(safeChannel);
			amqpTransport.unregisterIncoming(safeChannel);
		}
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = java.util.Objects.requireNonNull(channel, "channel");
		messenger.unregisterIncomingPluginChannel(owner, safeChannel);
		if (messenger.getIncomingChannelRegistrations(safeChannel).isEmpty()) {
			fallbackBus.unregisterIncoming(safeChannel);
			amqpTransport.unregisterIncoming(safeChannel);
		}
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner) {
		Set<PluginMessageChannel> channels = messenger.getIncomingChannels(owner);
		messenger.unregisterIncomingPluginChannel(owner);
		for (PluginMessageChannel channel : channels) {
			if (messenger.getIncomingChannelRegistrations(channel).isEmpty()) {
				fallbackBus.unregisterIncoming(channel);
				amqpTransport.unregisterIncoming(channel);
			}
		}
	}

	@Override
	public void registerOutgoingPluginChannel(Object owner, PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = java.util.Objects.requireNonNull(channel, "channel");
		boolean shouldRegisterTransport = !messenger.getOutgoingChannels().contains(safeChannel);
		messenger.registerOutgoingPluginChannel(owner, safeChannel);
		if (shouldRegisterTransport) {
			fallbackBus.registerOutgoing(safeChannel);
			amqpTransport.registerOutgoing(safeChannel);
		}
	}

	@Override
	public void unregisterOutgoingPluginChannel(Object owner, PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = java.util.Objects.requireNonNull(channel, "channel");
		messenger.unregisterOutgoingPluginChannel(owner, safeChannel);
		if (!messenger.getOutgoingChannels().contains(safeChannel)) {
			fallbackBus.unregisterOutgoing(safeChannel);
			amqpTransport.unregisterOutgoing(safeChannel);
		}
	}

	@Override
	public void unregisterOutgoingPluginChannel(Object owner) {
		Set<PluginMessageChannel> channels = messenger.getOutgoingChannels(owner);
		messenger.unregisterOutgoingPluginChannel(owner);
		for (PluginMessageChannel channel : channels) {
			if (!messenger.getOutgoingChannels().contains(channel)) {
				fallbackBus.unregisterOutgoing(channel);
				amqpTransport.unregisterOutgoing(channel);
			}
		}
	}

	@Override
	public Set<PluginMessageChannel> getOutgoingChannels() {
		return messenger.getOutgoingChannels();
	}

	@Override
	public Set<PluginMessageChannel> getOutgoingChannels(Object owner) {
		return messenger.getOutgoingChannels(owner);
	}

	@Override
	public Set<PluginMessageChannel> getIncomingChannels() {
		return messenger.getIncomingChannels();
	}

	@Override
	public Set<PluginMessageChannel> getIncomingChannels(Object owner) {
		return messenger.getIncomingChannels(owner);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, Object>> getIncomingChannelRegistrations(Object owner) {
		return messenger.getIncomingChannelRegistrations(owner);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, Object>> getIncomingChannelRegistrations(PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(channel);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, Object>> getIncomingChannelRegistrations(Object owner, PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(owner, channel);
	}

	@Override
	public boolean isRegistrationValid(PluginMessageListenerRegistration<Object, Object> registration) {
		return messenger.isRegistrationValid(registration);
	}

	@Override
	public boolean isIncomingChannelRegistered(Object owner, PluginMessageChannel channel) {
		return messenger.isIncomingChannelRegistered(owner, channel);
	}

	@Override
	public boolean isOutgoingChannelRegistered(Object owner, PluginMessageChannel channel) {
		return messenger.isOutgoingChannelRegistered(owner, channel);
	}

	@Override
	public PluginMessageDispatchResult dispatchIncomingMessage(Object source, PluginMessageChannel channel, byte[] payload) {
		return messenger.dispatchIncomingMessage(source, channel, payload);
	}

	@Override
	public void clear() {
		for (PluginMessageChannel channel : messenger.getIncomingChannels()) {
			fallbackBus.unregisterIncoming(channel);
			amqpTransport.unregisterIncoming(channel);
		}

		for (PluginMessageChannel channel : messenger.getOutgoingChannels()) {
			fallbackBus.unregisterOutgoing(channel);
			amqpTransport.unregisterOutgoing(channel);
		}

		messenger.clear();
	}

	@Override
	public boolean send(Object target, PluginMessageChannel channel, byte[] payload) {
		if (amqpTransport.canSend(target) && amqpTransport.send(target, channel, payload)) {
			return true;
		}
		return fallbackBus.send(target, channel, payload);
	}

	@Override
	public void close() {
		clear();
		amqpTransport.close();
		fallbackBus.close();
	}

	private PluginMessageDispatchResult dispatchIncomingContext(PluginMessageContext<Object> context) {
		return messenger.dispatchIncomingMessage(context.source(), context.channel(), context.payload());
	}
}
