package dev.belikhun.luna.core.velocity.messaging;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityPluginMessagingBus implements PluginMessageBus<Object, Object> {
	private final ProxyServer proxyServer;
	private final Object plugin;
	private final LunaLogger logger;
	private final Map<String, PluginMessageHandler<Object>> incomingHandlers;
	private final Set<String> outgoingChannels;

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger) {
		this.proxyServer = proxyServer;
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging");
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
		this.proxyServer.getEventManager().register(plugin, this);
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Object> handler) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		incomingHandlers.put(identifier.getId(), handler);
		proxyServer.getChannelRegistrar().register(identifier);
		logger.debug("Đã đăng ký incoming plugin channel: " + identifier.getId());
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		if (incomingHandlers.remove(identifier.getId()) != null) {
			if (!outgoingChannels.contains(identifier.getId())) {
				proxyServer.getChannelRegistrar().unregister(identifier);
			}
			logger.debug("Đã hủy incoming plugin channel: " + identifier.getId());
		}
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		if (outgoingChannels.add(identifier.getId())) {
			proxyServer.getChannelRegistrar().register(identifier);
			logger.debug("Đã đăng ký outgoing plugin channel: " + identifier.getId());
		}
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		if (outgoingChannels.remove(identifier.getId())) {
			if (!incomingHandlers.containsKey(identifier.getId())) {
				proxyServer.getChannelRegistrar().unregister(identifier);
			}
			logger.debug("Đã hủy outgoing plugin channel: " + identifier.getId());
		}
	}

	@Override
	public boolean send(Object target, PluginMessageChannel channel, byte[] payload) {
		ChannelIdentifier identifier = toIdentifier(channel);
		if (!outgoingChannels.contains(identifier.getId())) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + identifier.getId());
		}

		if (target instanceof Player player) {
			boolean sent = player.sendPluginMessage(identifier, payload);
			logger.debug("[TX] proxy->player channel=" + identifier.getId()
				+ " target=" + player.getUsername()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
			return sent;
		}

		if (target instanceof RegisteredServer registeredServer) {
			boolean sent = registeredServer.sendPluginMessage(identifier, payload);
			logger.debug("[TX] proxy->backend channel=" + identifier.getId()
				+ " target=" + registeredServer.getServerInfo().getName()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
			return sent;
		}

		if (target instanceof ServerConnection serverConnection) {
			boolean sent = serverConnection.sendPluginMessage(identifier, payload);
			logger.debug("[TX] proxy->backend-connection channel=" + identifier.getId()
				+ " target=" + serverConnection.getServerInfo().getName()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
			return sent;
		}

		throw new PluginMessagingException("Không hỗ trợ target type cho plugin messaging: " + target.getClass().getName());
	}

	@Override
	public void close() {
		proxyServer.getEventManager().unregisterListeners(plugin);

		for (String channel : incomingHandlers.keySet()) {
			proxyServer.getChannelRegistrar().unregister(MinecraftChannelIdentifier.from(channel));
		}

		for (String channel : outgoingChannels) {
			proxyServer.getChannelRegistrar().unregister(MinecraftChannelIdentifier.from(channel));
		}

		incomingHandlers.clear();
		outgoingChannels.clear();
	}

	@Subscribe
	public void onPluginMessage(PluginMessageEvent event) {
		if (!(event.getIdentifier() instanceof MinecraftChannelIdentifier identifier)) {
			return;
		}

		logger.debug("[RX] channel=" + identifier.getId()
			+ " source=" + event.getSource().getClass().getSimpleName()
			+ " bytes=" + event.getData().length);

		PluginMessageHandler<Object> handler = incomingHandlers.get(identifier.getId());
		if (handler == null) {
			logger.debug("[RX] Không có handler cho channel=" + identifier.getId());
			return;
		}

		event.setResult(PluginMessageEvent.ForwardResult.handled());
		PluginMessageDispatchResult dispatchResult = handler.handle(
			new PluginMessageContext<>(PluginMessageChannel.of(identifier.getId()), event.getSource(), event.getData())
		);
		if (dispatchResult == PluginMessageDispatchResult.PASS_THROUGH) {
			event.setResult(PluginMessageEvent.ForwardResult.forward());
		}

		logger.debug("[RX] Đã xử lý channel=" + identifier.getId() + " result=" + dispatchResult.name());
	}

	private MinecraftChannelIdentifier toIdentifier(PluginMessageChannel channel) {
		return MinecraftChannelIdentifier.from(channel.value());
	}
}
