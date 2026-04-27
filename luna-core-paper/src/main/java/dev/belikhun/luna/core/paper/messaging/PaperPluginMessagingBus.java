package dev.belikhun.luna.core.paper.messaging;

import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageListenerRegistration;
import dev.belikhun.luna.core.api.messaging.StandardPluginMessenger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.Set;
import java.util.function.Supplier;

public final class PaperPluginMessagingBus implements PluginMessageBus<Player, Player> {
	private final PaperBungeePluginMessagingBus fallbackBus;
	private final PaperAmqpMessagingTransport amqpTransport;
	private final StandardPluginMessenger<Object, Player> messenger;

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger) {
		this(plugin, logger, false);
	}

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger, boolean loggingEnabled) {
		this(plugin, logger, loggingEnabled, () -> {
			String host = plugin.getServer().getIp();
			if (host == null || host.isBlank()) {
				host = "127.0.0.1";
			}
			return new BackendMetadata(host + ":" + plugin.getServer().getPort(), "", "").sanitize();
		});
	}

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger, boolean loggingEnabled, Supplier<BackendMetadata> localBackendMetadataSupplier) {
		this.fallbackBus = new PaperBungeePluginMessagingBus(plugin, logger, loggingEnabled);
		this.amqpTransport = new PaperAmqpMessagingTransport(plugin, logger, loggingEnabled, localBackendMetadataSupplier);
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
	public PluginMessageListenerRegistration<Object, Player> registerIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<Player> handler) {
		PluginMessageChannel safeChannel = java.util.Objects.requireNonNull(channel, "channel");
		boolean shouldRegisterTransport = messenger.getIncomingChannelRegistrations(safeChannel).isEmpty();
		PluginMessageListenerRegistration<Object, Player> registration = messenger.registerIncomingPluginChannel(owner, safeChannel, handler);
		if (shouldRegisterTransport) {
			fallbackBus.registerIncoming(safeChannel, this::dispatchIncomingContext);
			amqpTransport.registerIncoming(safeChannel, this::dispatchIncomingContext);
		}
		return registration;
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<Player> handler) {
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
	public Set<PluginMessageListenerRegistration<Object, Player>> getIncomingChannelRegistrations(Object owner) {
		return messenger.getIncomingChannelRegistrations(owner);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, Player>> getIncomingChannelRegistrations(PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(channel);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, Player>> getIncomingChannelRegistrations(Object owner, PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(owner, channel);
	}

	@Override
	public boolean isRegistrationValid(PluginMessageListenerRegistration<Object, Player> registration) {
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
	public PluginMessageDispatchResult dispatchIncomingMessage(Player source, PluginMessageChannel channel, byte[] payload) {
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
	public boolean send(Player target, PluginMessageChannel channel, byte[] payload) {
		if (amqpTransport.send(target, channel, payload)) {
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

	private PluginMessageDispatchResult dispatchIncomingContext(PluginMessageContext<Player> context) {
		return messenger.dispatchIncomingMessage(context.source(), context.channel(), context.payload());
	}
}
