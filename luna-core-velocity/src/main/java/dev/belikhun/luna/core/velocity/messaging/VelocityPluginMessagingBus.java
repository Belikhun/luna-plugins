package dev.belikhun.luna.core.velocity.messaging;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
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
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public final class VelocityPluginMessagingBus implements PluginMessageBus<Object, Object> {
	private static final long INITIAL_CONNECTION_THROTTLE_MILLIS = 1000L;

	private final ProxyServer proxyServer;
	private final Object plugin;
	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Map<String, PluginMessageHandler<Object>> incomingHandlers;
	private final Set<String> outgoingChannels;
	private final Map<UUID, UnsafeBackendWindow> unsafeBackendWindows;
	private final Map<String, Long> unsafeServerUntil;

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
		this.unsafeBackendWindows = new ConcurrentHashMap<>();
		this.unsafeServerUntil = new ConcurrentHashMap<>();
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
			return sendToPlayer(identifier, payload, player, false);
		}

		if (target instanceof RegisteredServer registeredServer) {
			String serverName = registeredServer.getServerInfo().getName();
			long remainingThrottleMillis = remainingServerThrottleMillis(serverName);
			if (remainingThrottleMillis > 0L) {
				scheduleServerSend(serverName, identifier, payload, remainingThrottleMillis);
				logThrottle("proxy->backend", identifier.getId(), serverName, payload.length, remainingThrottleMillis);
				return true;
			}

			return sendToRegisteredServer(identifier, payload, registeredServer, false);
		}

		if (target instanceof ServerConnection serverConnection) {
			UUID playerUuid = serverConnection.getPlayer().getUniqueId();
			String serverName = serverConnection.getServerInfo().getName();
			long remainingThrottleMillis = remainingConnectionThrottleMillis(playerUuid, serverName);
			if (remainingThrottleMillis > 0L) {
				scheduleConnectionSend(playerUuid, serverName, identifier, payload, remainingThrottleMillis);
				logThrottle("proxy->backend-connection", identifier.getId(), serverName + "/" + serverConnection.getPlayer().getUsername(), payload.length, remainingThrottleMillis);
				return true;
			}

			return sendToServerConnection(identifier, payload, serverConnection, false);
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
		unsafeBackendWindows.clear();
		unsafeServerUntil.clear();
	}

	@Subscribe
	public void onServerConnected(ServerConnectedEvent event) {
		markBackendWindowUnsafe(event.getPlayer(), event.getServer().getServerInfo().getName());
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		ServerConnection connection = event.getPlayer().getCurrentServer().orElse(null);
		if (connection == null) {
			return;
		}

		markBackendWindowUnsafe(event.getPlayer(), connection.getServerInfo().getName());
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		unsafeBackendWindows.remove(event.getPlayer().getUniqueId());
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

	private boolean sendToPlayer(ChannelIdentifier identifier, byte[] payload, Player player, boolean delayed) {
		boolean sent = player.sendPluginMessage(identifier, payload);
		if (isChannelLoggingEnabled(identifier.getId())) {
			logger.audit((delayed ? "[TX:DELAYED]" : "[TX]") + " proxy->player channel=" + identifier.getId()
				+ " target=" + player.getUsername()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
		}
		return sent;
	}

	private boolean sendToRegisteredServer(ChannelIdentifier identifier, byte[] payload, RegisteredServer registeredServer, boolean delayed) {
		boolean sent = registeredServer.sendPluginMessage(identifier, payload);
		if (isChannelLoggingEnabled(identifier.getId())) {
			logger.audit((delayed ? "[TX:DELAYED]" : "[TX]") + " proxy->backend channel=" + identifier.getId()
				+ " target=" + registeredServer.getServerInfo().getName()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
		}
		return sent;
	}

	private boolean sendToServerConnection(ChannelIdentifier identifier, byte[] payload, ServerConnection serverConnection, boolean delayed) {
		boolean sent = serverConnection.sendPluginMessage(identifier, payload);
		if (isChannelLoggingEnabled(identifier.getId())) {
			logger.audit((delayed ? "[TX:DELAYED]" : "[TX]") + " proxy->backend-connection channel=" + identifier.getId()
				+ " target=" + serverConnection.getServerInfo().getName()
				+ " bytes=" + payload.length
				+ " sent=" + sent);
		}
		return sent;
	}

	private long remainingConnectionThrottleMillis(UUID playerUuid, String serverName) {
		UnsafeBackendWindow window = unsafeBackendWindows.get(playerUuid);
		if (window == null || !window.serverName().equals(serverName)) {
			return 0L;
		}

		long remaining = window.unsafeUntilEpochMillis() - System.currentTimeMillis();
		if (remaining <= 0L) {
			unsafeBackendWindows.remove(playerUuid, window);
			return 0L;
		}

		return remaining;
	}

	private long remainingServerThrottleMillis(String serverName) {
		Long unsafeUntil = unsafeServerUntil.get(serverName);
		if (unsafeUntil == null) {
			return 0L;
		}

		long remaining = unsafeUntil - System.currentTimeMillis();
		if (remaining <= 0L) {
			unsafeServerUntil.remove(serverName, unsafeUntil);
			return 0L;
		}

		return remaining;
	}

	private void scheduleConnectionSend(UUID playerUuid, String expectedServerName, ChannelIdentifier identifier, byte[] payload, long delayMillis) {
		proxyServer.getScheduler().buildTask(plugin, () -> flushConnectionSend(playerUuid, expectedServerName, identifier, payload))
			.delay(Math.max(1L, delayMillis), TimeUnit.MILLISECONDS)
			.schedule();
	}

	private void scheduleServerSend(String serverName, ChannelIdentifier identifier, byte[] payload, long delayMillis) {
		proxyServer.getScheduler().buildTask(plugin, () -> flushServerSend(serverName, identifier, payload))
			.delay(Math.max(1L, delayMillis), TimeUnit.MILLISECONDS)
			.schedule();
	}

	private void flushConnectionSend(UUID playerUuid, String expectedServerName, ChannelIdentifier identifier, byte[] payload) {
		Player player = proxyServer.getPlayer(playerUuid).orElse(null);
		if (player == null) {
			return;
		}

		ServerConnection connection = player.getCurrentServer().orElse(null);
		if (connection == null) {
			return;
		}

		String currentServerName = connection.getServerInfo().getName();
		if (!currentServerName.equals(expectedServerName)) {
			return;
		}

		long remainingThrottleMillis = remainingConnectionThrottleMillis(playerUuid, expectedServerName);
		if (remainingThrottleMillis > 0L) {
			scheduleConnectionSend(playerUuid, expectedServerName, identifier, payload, remainingThrottleMillis);
			return;
		}

		sendToServerConnection(identifier, payload, connection, true);
	}

	private void flushServerSend(String serverName, ChannelIdentifier identifier, byte[] payload) {
		RegisteredServer registeredServer = proxyServer.getServer(serverName).orElse(null);
		if (registeredServer == null) {
			return;
		}

		long remainingThrottleMillis = remainingServerThrottleMillis(serverName);
		if (remainingThrottleMillis > 0L) {
			scheduleServerSend(serverName, identifier, payload, remainingThrottleMillis);
			return;
		}

		sendToRegisteredServer(identifier, payload, registeredServer, true);
	}

	private void logThrottle(String direction, String channelId, String target, int payloadBytes, long delayMillis) {
		if (!isChannelLoggingEnabled(channelId)) {
			return;
		}

		logger.audit("[TX:THROTTLED] " + direction + " channel=" + channelId
			+ " target=" + target
			+ " bytes=" + payloadBytes
			+ " delayMs=" + delayMillis);
	}

	private boolean isChannelLoggingEnabled(String channelId) {
		if (!loggingEnabled) {
			return false;
		}

		return channelId == null || !channelId.startsWith("tab:bridge-");
	}

	private void markBackendWindowUnsafe(Player player, String serverName) {
		if (player == null || serverName == null || serverName.isBlank()) {
			return;
		}

		long unsafeUntil = System.currentTimeMillis() + INITIAL_CONNECTION_THROTTLE_MILLIS;
		unsafeBackendWindows.compute(player.getUniqueId(), (ignored, existing) -> {
			if (existing != null && existing.serverName().equals(serverName) && existing.unsafeUntilEpochMillis() > unsafeUntil) {
				return existing;
			}

			return new UnsafeBackendWindow(serverName, unsafeUntil);
		});
		unsafeServerUntil.merge(serverName, unsafeUntil, Math::max);
	}

	private record UnsafeBackendWindow(String serverName, long unsafeUntilEpochMillis) {
	}
}
