package dev.belikhun.luna.core.fabric.messaging;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;

import java.util.concurrent.atomic.AtomicReference;

public final class RabbitMqFabricAmqpBridge implements FabricAmqpBridge, FabricConfigurableAmqpBridge {
	private static final long RETRY_INTERVAL_MILLIS = 15000L;

	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Object lifecycleLock = new Object();
	private final AtomicReference<FabricAmqpEnvelopeConsumer> consumerRef = new AtomicReference<>();

	private volatile AmqpMessagingConfig config = AmqpMessagingConfig.disabled();
	private volatile Connection connection;
	private volatile Channel publishChannel;
	private volatile Channel consumerChannel;
	private volatile boolean active;
	private volatile long nextRetryAtMillis;

	public RabbitMqFabricAmqpBridge(LunaLogger logger, boolean loggingEnabled) {
		this.logger = logger.scope("FabricAmqpBridge");
		this.loggingEnabled = loggingEnabled;
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
				if (loggingEnabled && sanitized.enabled()) {
					logger.warn("AMQP được bật nhưng cấu hình chưa đủ. Fabric bridge sẽ tạm dừng.");
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
	public boolean publish(String routingKey, byte[] envelopePayload) {
		if (routingKey == null || routingKey.isBlank() || envelopePayload == null) {
			return false;
		}

		if (!ensureActive()) {
			return false;
		}

		AmqpMessagingConfig currentConfig = config;
		try {
			synchronized (lifecycleLock) {
				Channel channel = publishChannel;
				if (channel == null || !channel.isOpen()) {
					closeTransportLocked(false);
					return false;
				}

				channel.basicPublish(currentConfig.exchange(), routingKey, null, envelopePayload);
			}

			if (loggingEnabled) {
				logger.debug("[AMQP] Published routingKey=" + routingKey + " bytes=" + envelopePayload.length);
			}
			return true;
		} catch (Exception exception) {
			handleTransportFailure("TX", exception);
			return false;
		}
	}

	@Override
	public void setConsumer(FabricAmqpEnvelopeConsumer consumer) {
		consumerRef.set(consumer);
	}

	@Override
	public void clearConsumer() {
		consumerRef.set(null);
	}

	@Override
	public void close() {
		synchronized (lifecycleLock) {
			closeTransportLocked(true);
		}
		consumerRef.set(null);
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
				logger.warn("Không thể khởi tạo Fabric RabbitMQ bridge. Sẽ thử lại sau " + (RETRY_INTERVAL_MILLIS / 1000L) + "s. Lý do: " + exception.getMessage());
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

		String localServerName = currentConfig.effectiveLocalServerName("fabric-backend");
		String normalizedServerName = currentConfig.normalizeServerName(localServerName);
		String queueName = currentConfig.backendQueue(localServerName);

		Connection nextConnection = factory.newConnection("luna-fabric-amqp-" + normalizedServerName);
		Channel nextPublishChannel = nextConnection.createChannel();
		Channel nextConsumerChannel = nextConnection.createChannel();
		nextPublishChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.queueDeclare(queueName, true, false, false, null);
		nextConsumerChannel.queueBind(queueName, currentConfig.exchange(), queueName);

		DeliverCallback deliverCallback = (consumerTag, delivery) -> {
			FabricAmqpEnvelopeConsumer consumer = consumerRef.get();
			if (consumer != null) {
				consumer.onEnvelope(delivery.getBody());
			}
		};
		CancelCallback cancelCallback = consumerTag -> logger.warn("AMQP consumer của Fabric bridge đã bị hủy: " + consumerTag);
		nextConsumerChannel.basicConsume(queueName, true, deliverCallback, cancelCallback);

		connection = nextConnection;
		publishChannel = nextPublishChannel;
		consumerChannel = nextConsumerChannel;
		active = true;
		nextRetryAtMillis = 0L;
		logger.success("Đã bật RabbitMQ bridge cho Fabric exchange=" + currentConfig.exchange() + " queue=" + queueName + " uri=" + currentConfig.maskedUri());
	}

	private void handleTransportFailure(String direction, Exception exception) {
		synchronized (lifecycleLock) {
			nextRetryAtMillis = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
			closeTransportLocked(false);
		}
		logger.warn("Fabric RabbitMQ bridge lỗi trong lúc " + direction + ". Lý do: " + exception.getMessage());
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
