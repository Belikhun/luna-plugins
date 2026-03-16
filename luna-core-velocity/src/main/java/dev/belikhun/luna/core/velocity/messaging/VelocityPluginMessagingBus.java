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

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityPluginMessagingBus implements PluginMessageBus<Object, Object> {
	private static final HexFormat HEX_FORMAT = HexFormat.of();

	private final ProxyServer proxyServer;
	private final Object plugin;
	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Map<String, PluginMessageHandler<Object>> incomingHandlers;
	private final Set<String> outgoingChannels;

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger) {
		this(proxyServer, plugin, logger, false);
	}

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger, boolean loggingEnabled) {
		this.proxyServer = proxyServer;
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging");
		this.loggingEnabled = loggingEnabled;
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
		this.proxyServer.getEventManager().register(plugin, this);
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Object> handler) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		incomingHandlers.put(identifier.getId(), handler);
		proxyServer.getChannelRegistrar().register(identifier);
		if (loggingEnabled) {
			logger.debug("Đã đăng ký incoming plugin channel: " + identifier.getId());
		}
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		if (incomingHandlers.remove(identifier.getId()) != null) {
			if (!outgoingChannels.contains(identifier.getId())) {
				proxyServer.getChannelRegistrar().unregister(identifier);
			}
			if (loggingEnabled) {
				logger.debug("Đã hủy incoming plugin channel: " + identifier.getId());
			}
		}
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		if (outgoingChannels.add(identifier.getId())) {
			proxyServer.getChannelRegistrar().register(identifier);
			if (loggingEnabled) {
				logger.debug("Đã đăng ký outgoing plugin channel: " + identifier.getId());
			}
		}
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		MinecraftChannelIdentifier identifier = toIdentifier(channel);
		if (outgoingChannels.remove(identifier.getId())) {
			if (!incomingHandlers.containsKey(identifier.getId())) {
				proxyServer.getChannelRegistrar().unregister(identifier);
			}
			if (loggingEnabled) {
				logger.debug("Đã hủy outgoing plugin channel: " + identifier.getId());
			}
		}
	}

	@Override
	public boolean send(Object target, PluginMessageChannel channel, byte[] payload) {
		ChannelIdentifier identifier = toIdentifier(channel);
		if (!outgoingChannels.contains(identifier.getId())) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + identifier.getId());
		}

		if (target instanceof Player player) {
			return sendToPlayer(identifier, payload, player);
		}

		if (target instanceof RegisteredServer registeredServer) {
			ServerConnection relay = resolveRelayConnection(registeredServer);
			if (relay == null) {
				if (isChannelLoggingEnabled(identifier.getId())) {
					logger.audit("[TX] proxy->backend channel=" + identifier.getId()
						+ " target=" + registeredServer.getServerInfo().getName()
						+ " bytes=" + payload.length
						+ " sent=false reason=NO_CONNECTION");
				}
				return false;
			}
			return sendToServerConnection(identifier, payload, relay);
		}

		if (target instanceof ServerConnection serverConnection) {
			return sendToServerConnection(identifier, payload, serverConnection);
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

		boolean channelLoggingEnabled = isChannelLoggingEnabled(identifier.getId());
		if (channelLoggingEnabled) {
			logger.audit("[RX] channel=" + identifier.getId()
				+ " source=" + event.getSource().getClass().getSimpleName()
				+ " bytes=" + event.getData().length);
		}

		PluginMessageHandler<Object> handler = incomingHandlers.get(identifier.getId());
		if (handler == null) {
			if (channelLoggingEnabled) {
				logger.debug("[RX] Không có handler cho channel=" + identifier.getId());
			}
			return;
		}

		event.setResult(PluginMessageEvent.ForwardResult.handled());
		PluginMessageDispatchResult dispatchResult = handler.handle(
			new PluginMessageContext<>(PluginMessageChannel.of(identifier.getId()), event.getSource(), event.getData())
		);
		if (dispatchResult == PluginMessageDispatchResult.PASS_THROUGH) {
			event.setResult(PluginMessageEvent.ForwardResult.forward());
		}

		if (channelLoggingEnabled) {
			logger.audit("[RX] Đã xử lý channel=" + identifier.getId() + " result=" + dispatchResult.name());
		}
	}

	private MinecraftChannelIdentifier toIdentifier(PluginMessageChannel channel) {
		return MinecraftChannelIdentifier.from(channel.value());
	}

	private boolean sendToPlayer(ChannelIdentifier identifier, byte[] payload, Player player) {
		boolean sent = player.sendPluginMessage(identifier, payload);
		if (isChannelLoggingEnabled(identifier.getId())) {
			logger.audit("[TX] proxy->player channel=" + identifier.getId()
				+ " target=" + player.getUsername()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
		}
		return sent;
	}

	private boolean sendToServerConnection(ChannelIdentifier identifier, byte[] payload, ServerConnection serverConnection) {
		if (loggingEnabled) {
			logger.audit("[TX:CUSTOM_PAYLOAD] proxy->backend channel=" + identifier.getId()
				+ " target=" + serverConnection.getServerInfo().getName() + "/" + serverConnection.getPlayer().getUsername()
				+ " bytes=" + payload.length
				+ " payloadHex=" + payloadHex(payload));
		}

		boolean sent = serverConnection.sendPluginMessage(identifier, payload);
		if (isChannelLoggingEnabled(identifier.getId())) {
			logger.audit("[TX] proxy->backend-connection channel=" + identifier.getId()
				+ " target=" + serverConnection.getServerInfo().getName()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
		}
		return sent;
	}

	private ServerConnection resolveRelayConnection(RegisteredServer registeredServer) {
		for (Player connectedPlayer : registeredServer.getPlayersConnected()) {
			ServerConnection currentConnection = connectedPlayer.getCurrentServer().orElse(null);
			if (currentConnection == null) {
				continue;
			}

			if (currentConnection.getServerInfo().getName().equals(registeredServer.getServerInfo().getName())) {
				return currentConnection;
			}
		}
		return null;
	}

	private boolean isChannelLoggingEnabled(String channelId) {
		if (!loggingEnabled) {
			return false;
		}

		return channelId == null || !channelId.startsWith("tab:bridge-");
	}

	private String payloadHex(byte[] payload) {
		if (payload == null || payload.length == 0) {
			return "<empty>";
		}

		return HEX_FORMAT.formatHex(payload);
	}

}
