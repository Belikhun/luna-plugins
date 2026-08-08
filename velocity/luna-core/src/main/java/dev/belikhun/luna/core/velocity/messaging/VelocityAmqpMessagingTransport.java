package dev.belikhun.luna.core.velocity.messaging;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpConnection;
import dev.belikhun.luna.core.api.messaging.AmqpEndpoint;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpPluginMessageEnvelope;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The proxy's side of the AMQP bus.
 *
 * The connection itself lives in {@link AmqpConnection}; what is left here is
 * the channel registry, the audit trail and the part only the proxy does - it
 * addresses a backend rather than a player, so its routing key is the target
 * server's queue and its own consumer sits on the proxy queue.
 */
final class VelocityAmqpMessagingTransport implements AmqpEndpoint {
	private final ProxyServer proxyServer;
	private final Object plugin;
	private final LunaLogger logger;
	private final Map<String, PluginMessageHandler<Object>> incomingHandlers;
	private final Set<String> outgoingChannels;
	private final AmqpConnection connection;

	private volatile boolean loggingEnabled;

	VelocityAmqpMessagingTransport(ProxyServer proxyServer, Object plugin, LunaLogger logger, boolean loggingEnabled) {
		this.proxyServer = proxyServer;
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.loggingEnabled = loggingEnabled;
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
		this.connection = new AmqpConnection(this, logger, "proxy");
	}

	void setLoggingEnabled(boolean loggingEnabled) {
		this.loggingEnabled = loggingEnabled;
	}

	void updateConfig(AmqpMessagingConfig nextConfig) {
		connection.updateConfig(nextConfig);
	}

	void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Object> handler) {
		incomingHandlers.put(channel.value(), handler);
	}

	void unregisterIncoming(PluginMessageChannel channel) {
		incomingHandlers.remove(channel.value());
	}

	void registerOutgoing(PluginMessageChannel channel) {
		outgoingChannels.add(channel.value());
	}

	void unregisterOutgoing(PluginMessageChannel channel) {
		outgoingChannels.remove(channel.value());
	}

	boolean canSend(Object target) {
		return target instanceof ServerConnection || target instanceof RegisteredServer;
	}

	boolean send(Object target, PluginMessageChannel channel, byte[] payload) {
		if (!outgoingChannels.contains(channel.value())) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + channel.value());
		}

		if (!canSend(target) || !connection.ensureReady()) {
			return false;
		}

		String targetServerName;
		String playerId = "";
		String playerName = "";

		if (target instanceof ServerConnection connectionTarget) {
			targetServerName = connectionTarget.getServerInfo().getName();
			playerId = connectionTarget.getPlayer().getUniqueId().toString();
			playerName = connectionTarget.getPlayer().getUsername();
		} else if (target instanceof RegisteredServer registeredServer) {
			targetServerName = registeredServer.getServerInfo().getName();
		} else {
			return false;
		}

		AmqpMessagingConfig currentConfig = connection.config();
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			targetServerName,
			playerId,
			playerName,
			targetServerName,
			payload
		);

		if (!connection.publish(currentConfig.backendQueue(targetServerName), envelope.encode())) {
			return false;
		}

		if (loggingEnabled) {
			logger.audit("[TX:AMQP] proxy->backend channel=" + channel.value()
				+ " target=" + targetServerName
				+ " bytes=" + payload.length);
		}

		return true;
	}

	boolean isActive() {
		return connection.isActive();
	}

	void close() {
		connection.close();
		incomingHandlers.clear();
		outgoingChannels.clear();
	}

	@Override
	public String connectionName(AmqpMessagingConfig config) {
		return "luna-velocity-amqp";
	}

	@Override
	public String consumerQueue(AmqpMessagingConfig config) {
		return config.proxyQueue();
	}

	/** Deliveries arrive on the client's own thread; handlers expect the proxy's. */
	@Override
	public void onDelivery(byte[] body) {
		proxyServer.getScheduler().buildTask(plugin, () -> dispatch(body)).schedule();
	}

	private void dispatch(byte[] body) {
		try {
			AmqpPluginMessageEnvelope envelope = AmqpPluginMessageEnvelope.decode(body);
			PluginMessageHandler<Object> handler = incomingHandlers.get(envelope.channel());

			if (handler == null) {
				if (loggingEnabled) {
					logger.debug("[RX:AMQP] Không có handler cho channel=" + envelope.channel());
				}

				return;
			}

			Object source = resolveSource(envelope);

			if (loggingEnabled) {
				logger.audit("[RX:AMQP] backend->proxy channel=" + envelope.channel()
					+ " source=" + describeSource(source)
					+ " bytes=" + envelope.payload().length);
			}

			PluginMessageDispatchResult result = handler.handle(new PluginMessageContext<>(PluginMessageChannel.of(envelope.channel()), source, envelope.payload()));

			if (loggingEnabled) {
				logger.audit("[RX:AMQP] Đã xử lý channel=" + envelope.channel() + " result=" + result.name());
			}
		} catch (Exception exception) {
			logger.warn("Không thể xử lý AMQP payload trên proxy: " + exception.getMessage());
		}
	}

	private Object resolveSource(AmqpPluginMessageEnvelope envelope) {
		if (envelope.sourcePlayerId() != null && !envelope.sourcePlayerId().isBlank()) {
			try {
				Player player = proxyServer.getPlayer(UUID.fromString(envelope.sourcePlayerId())).orElse(null);

				if (player != null) {
					ServerConnection playerConnection = player.getCurrentServer().orElse(null);

					// the backend that actually sent it is the more precise source,
					// but only when the envelope agrees with where the player is now
					if (playerConnection != null && envelope.sourceServerName() != null && !envelope.sourceServerName().isBlank()
						&& playerConnection.getServerInfo().getName().equalsIgnoreCase(envelope.sourceServerName())) {
						return playerConnection;
					}

					return player;
				}
			} catch (IllegalArgumentException ignored) {
				// a malformed id is still worth trying the server name for
			}
		}

		if (envelope.sourceServerName() != null && !envelope.sourceServerName().isBlank()) {
			return proxyServer.getServer(envelope.sourceServerName()).orElse(null);
		}

		return null;
	}

	private String describeSource(Object source) {
		if (source == null) {
			return "unknown";
		}

		if (source instanceof ServerConnection serverConnection) {
			return serverConnection.getServerInfo().getName() + "/" + serverConnection.getPlayer().getUsername();
		}

		if (source instanceof Player player) {
			return player.getUsername();
		}

		if (source instanceof RegisteredServer registeredServer) {
			return registeredServer.getServerInfo().getName();
		}

		return source.getClass().getSimpleName();
	}
}
