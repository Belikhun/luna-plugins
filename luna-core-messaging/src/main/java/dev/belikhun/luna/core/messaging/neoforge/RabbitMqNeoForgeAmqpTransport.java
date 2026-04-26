package dev.belikhun.luna.core.messaging.neoforge;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpPluginMessageEnvelope;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

final class RabbitMqNeoForgeAmqpTransport implements NeoForgeAmqpTransport {
	private static final long RETRY_INTERVAL_MILLIS = 15000L;

	private final NeoForgePluginMessagingBus bus;
	private final LunaLogger logger;
	private final Object lifecycleLock;

	private volatile AmqpMessagingConfig config;
	private volatile Connection connection;
	private volatile Channel publishChannel;
	private volatile Channel consumerChannel;
	private volatile boolean active;
	private volatile long nextRetryAtMillis;

	RabbitMqNeoForgeAmqpTransport(NeoForgePluginMessagingBus bus, LunaLogger logger) {
		this.bus = bus;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.lifecycleLock = new Object();
		this.config = AmqpMessagingConfig.disabled();
		this.active = false;
		this.nextRetryAtMillis = 0L;
	}

	@Override
	public void updateConfig(AmqpMessagingConfig config) {
		AmqpMessagingConfig sanitized = (config == null ? AmqpMessagingConfig.disabled() : config).sanitize();
		synchronized (lifecycleLock) {
			boolean changed = !sanitized.equals(this.config);
			this.config = sanitized;
			if (!sanitized.isConfigured()) {
				closeTransportLocked(false);
				if (sanitized.enabled()) {
					logger.warn("AMQP được bật nhưng cấu hình NeoForge chưa đủ. Dùng no-op transport.");
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

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		if (!ensureActive()) {
			return false;
		}

		AmqpMessagingConfig currentConfig = config;
		String localServerName = resolveLocalServerName(currentConfig);
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			localServerName,
			target.getUUID().toString(),
			target.getGameProfile().getName(),
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
			return true;
		} catch (Exception exception) {
			handleTransportFailure("TX", exception);
			return false;
		}
	}

	@Override
	public void close() {
		synchronized (lifecycleLock) {
			closeTransportLocked(true);
		}
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
				logger.warn("Không thể khởi tạo AMQP transport trên NeoForge. Sẽ thử lại sau " + (RETRY_INTERVAL_MILLIS / 1000L) + "s. Lý do: " + exception.getMessage());
				return false;
			}
		}
	}

	private void openTransportLocked(AmqpMessagingConfig currentConfig) throws Exception {
		closeTransportLocked(false);

		String serverName = resolveLocalServerName(currentConfig);
		String queueName = currentConfig.backendQueue(serverName);

		ConnectionFactory factory = new ConnectionFactory();
		factory.setUri(currentConfig.uri());
		factory.setConnectionTimeout(currentConfig.connectionTimeoutMillis());
		factory.setRequestedHeartbeat(currentConfig.requestedHeartbeatSeconds());
		factory.setAutomaticRecoveryEnabled(false);

		Connection nextConnection = factory.newConnection("luna-neoforge-amqp-" + currentConfig.normalizeServerName(serverName));
		Channel nextPublishChannel = nextConnection.createChannel();
		Channel nextConsumerChannel = nextConnection.createChannel();
		nextPublishChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.queueDeclare(queueName, true, false, false, null);
		nextConsumerChannel.queueBind(queueName, currentConfig.exchange(), queueName);

		DeliverCallback deliverCallback = (consumerTag, delivery) -> scheduleDispatch(delivery.getBody());
		CancelCallback cancelCallback = consumerTag -> logger.warn("AMQP consumer của NeoForge đã bị hủy: " + consumerTag);
		nextConsumerChannel.basicConsume(queueName, true, deliverCallback, cancelCallback);

		connection = nextConnection;
		publishChannel = nextPublishChannel;
		consumerChannel = nextConsumerChannel;
		active = true;
		nextRetryAtMillis = 0L;
		logger.success("Đã bật AMQP transport cho NeoForge backend=" + serverName + " exchange=" + currentConfig.exchange() + " queue=" + queueName + " uri=" + currentConfig.maskedUri());
	}

	private void scheduleDispatch(byte[] body) {
		MinecraftServer server = LunaCoreNeoForge.services().server();
		server.execute(() -> dispatch(body));
	}

	private void dispatch(byte[] body) {
		try {
			AmqpPluginMessageEnvelope envelope = AmqpPluginMessageEnvelope.decode(body);
			PluginMessageChannel channel = PluginMessageChannel.of(envelope.channel());
			ServerPlayer source = resolvePlayer(envelope.sourcePlayerId(), envelope.sourcePlayerName());
			PluginMessageDispatchResult result = bus.dispatchIncoming(source, channel, envelope.payload());
			logger.debug("[RX:AMQP] Đã xử lý channel=" + channel + " result=" + result.name());
		} catch (Exception exception) {
			logger.warn("Không thể xử lý AMQP payload trên NeoForge: " + exception.getMessage());
		}
	}

	private ServerPlayer resolvePlayer(String playerId, String playerName) {
		MinecraftServer server = LunaCoreNeoForge.services().server();
		if (playerId != null && !playerId.isBlank()) {
			try {
				return server.getPlayerList().getPlayer(UUID.fromString(playerId));
			} catch (IllegalArgumentException ignored) {
			}
		}

		if (playerName != null && !playerName.isBlank()) {
			return server.getPlayerList().getPlayerByName(playerName);
		}

		return null;
	}

	private String resolveLocalServerName(AmqpMessagingConfig currentConfig) {
		String fallback = LunaCoreNeoForge.services().server().isDedicatedServer() ? "backend" : "integrated";
		String resolved = currentConfig.effectiveLocalServerName(fallback);
		if (resolved.isBlank()) {
			throw new IllegalStateException("Thiếu localServerName cho NeoForge AMQP transport.");
		}

		return resolved;
	}

	private void handleTransportFailure(String direction, Exception exception) {
		synchronized (lifecycleLock) {
			nextRetryAtMillis = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
			closeTransportLocked(false);
		}
		logger.warn("AMQP transport lỗi trong lúc " + direction + ". Lý do: " + exception.getMessage());
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
