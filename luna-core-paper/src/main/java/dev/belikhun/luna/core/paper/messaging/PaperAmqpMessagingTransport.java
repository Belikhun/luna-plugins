package dev.belikhun.luna.core.paper.messaging;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpPluginMessageEnvelope;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class PaperAmqpMessagingTransport {
	private static final long RETRY_INTERVAL_MILLIS = 15000L;

	private final Plugin plugin;
	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Supplier<BackendMetadata> localBackendMetadataSupplier;
	private final Map<String, PluginMessageHandler<Player>> incomingHandlers;
	private final Set<String> outgoingChannels;
	private final Object lifecycleLock;

	private volatile AmqpMessagingConfig config;
	private volatile Connection connection;
	private volatile Channel publishChannel;
	private volatile Channel consumerChannel;
	private volatile boolean active;
	private volatile long nextRetryAtMillis;

	PaperAmqpMessagingTransport(Plugin plugin, LunaLogger logger, boolean loggingEnabled, Supplier<BackendMetadata> localBackendMetadataSupplier) {
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.loggingEnabled = loggingEnabled;
		this.localBackendMetadataSupplier = localBackendMetadataSupplier;
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

	void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Player> handler) {
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

	boolean send(Player target, PluginMessageChannel channel, byte[] payload) {
		if (!outgoingChannels.contains(channel.value())) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + channel.value());
		}

		if (!ensureActive()) {
			return false;
		}

		AmqpMessagingConfig currentConfig = config;
		BackendMetadata localBackendMetadata = currentConfig.effectiveLocalBackendMetadata(localBackendMetadataSupplier.get());
		String localServerName = localBackendMetadata.name();
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			localServerName,
			target.getUniqueId().toString(),
			target.getName(),
			"",
			payload
		);

		try {
			synchronized (lifecycleLock) {
				Channel currentPublishChannel = publishChannel;
				if (currentPublishChannel == null || !currentPublishChannel.isOpen()) {
					closeTransportLocked(false);
					return false;
				}
				currentPublishChannel.basicPublish(currentConfig.exchange(), currentConfig.proxyQueue(), null, envelope.encode());
			}
			if (loggingEnabled) {
				logger.audit("[TX:AMQP] backend->proxy channel=" + channel.value()
					+ " source=" + target.getName()
					+ " queue=" + currentConfig.proxyQueue()
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
				logger.warn("Không thể khởi tạo AMQP transport cho backend. Dùng fallback plugin messaging trong " + (RETRY_INTERVAL_MILLIS / 1000L) + "s tới. Lý do: " + exception.getMessage());
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

		BackendMetadata localBackendMetadata = currentConfig.effectiveLocalBackendMetadata(localBackendMetadataSupplier.get());
		String serverName = localBackendMetadata.name();
		String queueName = currentConfig.backendQueue(serverName);
		Connection nextConnection = factory.newConnection("luna-paper-amqp-" + currentConfig.normalizeServerName(serverName));
		Channel nextPublishChannel = nextConnection.createChannel();
		Channel nextConsumerChannel = nextConnection.createChannel();
		nextPublishChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.queueDeclare(queueName, true, false, false, null);
		nextConsumerChannel.queueBind(queueName, currentConfig.exchange(), queueName);

		DeliverCallback deliverCallback = (consumerTag, delivery) -> scheduleDispatch(delivery.getBody());
		CancelCallback cancelCallback = consumerTag -> logger.warn("AMQP consumer của backend đã bị hủy: " + consumerTag);
		nextConsumerChannel.basicConsume(queueName, true, deliverCallback, cancelCallback);

		connection = nextConnection;
		publishChannel = nextPublishChannel;
		consumerChannel = nextConsumerChannel;
		active = true;
		nextRetryAtMillis = 0L;
		logger.success("Đã bật AMQP transport cho backend=" + serverName + " exchange=" + currentConfig.exchange() + " queue=" + queueName + " uri=" + currentConfig.maskedUri());
	}

	private void scheduleDispatch(byte[] body) {
		plugin.getServer().getScheduler().runTask(plugin, () -> dispatch(body));
	}

	private void dispatch(byte[] body) {
		try {
			AmqpPluginMessageEnvelope envelope = AmqpPluginMessageEnvelope.decode(body);
			PluginMessageHandler<Player> handler = incomingHandlers.get(envelope.channel());
			if (handler == null) {
				if (loggingEnabled) {
					logger.debug("[RX:AMQP] Không có handler cho channel=" + envelope.channel());
				}
				return;
			}

			Player source = resolvePlayer(envelope.sourcePlayerId(), envelope.sourcePlayerName());
			if (loggingEnabled) {
				logger.audit("[RX:AMQP] proxy->backend channel=" + envelope.channel()
					+ " source=" + (source == null ? "unknown" : source.getName())
					+ " bytes=" + envelope.payload().length);
			}

			handler.handle(new PluginMessageContext<>(PluginMessageChannel.of(envelope.channel()), source, envelope.payload()));
		} catch (Exception exception) {
			logger.warn("Không thể xử lý AMQP payload cho backend: " + exception.getMessage());
		}
	}

	private Player resolvePlayer(String playerId, String playerName) {
		if (playerId != null && !playerId.isBlank()) {
			try {
				return plugin.getServer().getPlayer(UUID.fromString(playerId));
			} catch (IllegalArgumentException ignored) {
			}
		}

		if (playerName != null && !playerName.isBlank()) {
			return plugin.getServer().getPlayer(playerName);
		}

		return null;
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
