package dev.belikhun.luna.core.velocity.messaging;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.logging.LunaLogger;
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

final class VelocityAmqpMessagingTransport {
	private static final long RETRY_INTERVAL_MILLIS = 15000L;

	private final ProxyServer proxyServer;
	private final Object plugin;
	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Map<String, PluginMessageHandler<Object>> incomingHandlers;
	private final Set<String> outgoingChannels;
	private final Object lifecycleLock;

	private volatile AmqpMessagingConfig config;
	private volatile Connection connection;
	private volatile Channel publishChannel;
	private volatile Channel consumerChannel;
	private volatile boolean active;
	private volatile long nextRetryAtMillis;

	VelocityAmqpMessagingTransport(ProxyServer proxyServer, Object plugin, LunaLogger logger, boolean loggingEnabled) {
		this.proxyServer = proxyServer;
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.loggingEnabled = loggingEnabled;
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
		this.lifecycleLock = new Object();
		this.config = AmqpMessagingConfig.disabled();
		this.active = false;
		this.nextRetryAtMillis = 0L;
	}

	void updateConfig(AmqpMessagingConfig nextConfig) {
		AmqpMessagingConfig sanitized = (nextConfig == null ? AmqpMessagingConfig.disabled() : nextConfig).sanitize();
		synchronized (lifecycleLock) {
			boolean changed = !sanitized.equals(this.config);
			this.config = sanitized;
			if (!sanitized.isConfigured()) {
				closeTransportLocked(false);
				if (loggingEnabled && sanitized.enabled()) {
					logger.warn("AMQP được bật nhưng cấu hình chưa đủ. Tiếp tục fallback plugin messaging.");
				}
				return;
			}

			if (changed) {
				closeTransportLocked(false);
				nextRetryAtMillis = 0L;
			}
		}

		ensureActive();
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

		if (!canSend(target) || !ensureActive()) {
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

		AmqpMessagingConfig currentConfig = config;
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			targetServerName,
			playerId,
			playerName,
			targetServerName,
			payload
		);

		try {
			synchronized (lifecycleLock) {
				Channel currentPublishChannel = publishChannel;
				if (currentPublishChannel == null || !currentPublishChannel.isOpen()) {
					closeTransportLocked(false);
					return false;
				}
				currentPublishChannel.basicPublish(currentConfig.exchange(), currentConfig.backendQueue(targetServerName), null, envelope.encode());
			}
			if (loggingEnabled) {
				logger.audit("[TX:AMQP] proxy->backend channel=" + channel.value()
					+ " target=" + targetServerName
					+ " bytes=" + payload.length);
			}
			return true;
		} catch (Exception exception) {
			handleTransportFailure("TX", exception);
			return false;
		}
	}

	boolean isActive() {
		return active;
	}

	void close() {
		synchronized (lifecycleLock) {
			closeTransportLocked(true);
		}
		incomingHandlers.clear();
		outgoingChannels.clear();
	}

	private boolean ensureActive() {
		AmqpMessagingConfig currentConfig = config;
		if (!currentConfig.isConfigured()) {
			return false;
		}

		if (active && connection != null && connection.isOpen() && publishChannel != null && publishChannel.isOpen()) {
			return true;
		}

		long now = System.currentTimeMillis();
		if (now < nextRetryAtMillis) {
			return false;
		}

		synchronized (lifecycleLock) {
			if (active && connection != null && connection.isOpen() && publishChannel != null && publishChannel.isOpen()) {
				return true;
			}

			if (System.currentTimeMillis() < nextRetryAtMillis) {
				return false;
			}

			try {
				openTransportLocked(currentConfig);
				return true;
			} catch (Exception exception) {
				nextRetryAtMillis = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
				closeTransportLocked(false);
				logger.warn("Không thể khởi tạo AMQP transport trên proxy. Dùng fallback plugin messaging trong " + (RETRY_INTERVAL_MILLIS / 1000L) + "s tới. Lý do: " + exception.getMessage());
				return false;
			}
		}
	}

	private void openTransportLocked(AmqpMessagingConfig currentConfig) throws Exception {
		closeTransportLocked(false);

		ConnectionFactory factory = new ConnectionFactory();
		factory.setUri(currentConfig.uri());
		factory.setConnectionTimeout(currentConfig.connectionTimeoutMillis());
		factory.setRequestedHeartbeat(currentConfig.requestedHeartbeatSeconds());
		factory.setAutomaticRecoveryEnabled(false);

		Connection nextConnection = factory.newConnection("luna-velocity-amqp");
		Channel nextPublishChannel = nextConnection.createChannel();
		Channel nextConsumerChannel = nextConnection.createChannel();
		nextPublishChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.queueDeclare(currentConfig.proxyQueue(), true, false, false, null);
		nextConsumerChannel.queueBind(currentConfig.proxyQueue(), currentConfig.exchange(), currentConfig.proxyQueue());

		DeliverCallback deliverCallback = (consumerTag, delivery) -> scheduleDispatch(delivery.getBody());
		CancelCallback cancelCallback = consumerTag -> logger.warn("AMQP consumer của proxy đã bị hủy: " + consumerTag);
		nextConsumerChannel.basicConsume(currentConfig.proxyQueue(), true, deliverCallback, cancelCallback);

		connection = nextConnection;
		publishChannel = nextPublishChannel;
		consumerChannel = nextConsumerChannel;
		active = true;
		nextRetryAtMillis = 0L;
		logger.success("Đã bật AMQP transport trên proxy exchange=" + currentConfig.exchange() + " queue=" + currentConfig.proxyQueue() + " uri=" + currentConfig.maskedUri());
	}

	private void scheduleDispatch(byte[] body) {
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
					ServerConnection connection = player.getCurrentServer().orElse(null);
					if (connection != null && envelope.sourceServerName() != null && !envelope.sourceServerName().isBlank()
						&& connection.getServerInfo().getName().equalsIgnoreCase(envelope.sourceServerName())) {
						return connection;
					}
					return player;
				}
			} catch (IllegalArgumentException ignored) {
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

	private void handleTransportFailure(String direction, Exception exception) {
		synchronized (lifecycleLock) {
			nextRetryAtMillis = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
			closeTransportLocked(false);
		}
		logger.warn("AMQP transport lỗi trong lúc " + direction + ". Fallback về plugin messaging. Lý do: " + exception.getMessage());
	}

	private void closeTransportLocked(boolean shutdown) {
		active = false;
		closeQuietly(consumerChannel);
		closeQuietly(publishChannel);
		closeQuietly(connection);
		consumerChannel = null;
		publishChannel = null;
		connection = null;
		if (shutdown) {
			nextRetryAtMillis = Long.MAX_VALUE;
		}
	}

	private void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}

		try {
			closeable.close();
		} catch (Exception ignored) {
		}
	}
}
