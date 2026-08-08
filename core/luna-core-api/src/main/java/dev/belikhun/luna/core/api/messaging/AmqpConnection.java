package dev.belikhun.luna.core.api.messaging;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import dev.belikhun.luna.core.api.logging.LunaLogger;

/**
 * The broker half of the plugin-message bus, shared by every platform.
 *
 * The connection is opened lazily and reopened on demand rather than kept alive
 * by a watchdog: a side that cannot reach the broker still has to serve players,
 * so every failure closes the transport, arms a retry window and lets the next
 * message try again. Until the window passes, callers fall back to the platform's
 * own plugin messaging.
 *
 * A platform supplies an {@link AmqpEndpoint} and keeps only what is genuinely
 * its own: building an envelope, choosing a routing key, and dispatching an
 * inbound body to its listeners.
 */
public final class AmqpConnection implements AutoCloseable {
	private static final long RETRY_INTERVAL_MILLIS = 15000L;

	private final AmqpEndpoint endpoint;
	private final LunaLogger logger;
	private final String label;
	private final Object lifecycleLock;

	private volatile AmqpMessagingConfig config;
	private volatile Connection connection;
	private volatile Channel publishChannel;
	private volatile Channel consumerChannel;
	private volatile boolean active;
	private volatile long nextRetryAtMillis;

	/**
	 * @param endpoint what this side consumes and how it handles a delivery
	 * @param logger   scoped to PluginMessaging/AMQP by this constructor
	 * @param label    names this side in log lines, e.g. {@code backend} or {@code proxy}
	 */
	public AmqpConnection(AmqpEndpoint endpoint, LunaLogger logger, String label) {
		this.endpoint = endpoint;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.label = label;
		this.lifecycleLock = new Object();
		this.config = AmqpMessagingConfig.disabled();
		this.active = false;
		this.nextRetryAtMillis = 0L;
	}

	/**
	 * Applies a configuration, reopening the connection when it actually changed.
	 *
	 * @param nextConfig the new configuration, null being the same as disabled
	 */
	public void updateConfig(AmqpMessagingConfig nextConfig) {
		AmqpMessagingConfig sanitized = (nextConfig == null ? AmqpMessagingConfig.disabled() : nextConfig).sanitize();

		synchronized (lifecycleLock) {
			boolean changed = !sanitized.equals(this.config);
			this.config = sanitized;

			if (!sanitized.isConfigured()) {
				closeTransportLocked(false);

				if (sanitized.enabled()) {
					logger.warn("AMQP được bật nhưng cấu hình của " + label + " chưa đủ. Tiếp tục dùng fallback plugin messaging.");
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

	/** The configuration currently in force, already sanitized. */
	public AmqpMessagingConfig config() {
		return config;
	}

	/** Whether the broker connection is open and usable right now. */
	public boolean isActive() {
		return active;
	}

	/**
	 * Opens the connection if it is not up and the retry window has passed.
	 *
	 * Callers use this to bail out before doing the work of building an envelope,
	 * which on some platforms reads state that only exists once configured.
	 *
	 * @return false when the broker is unreachable, so the caller can fall back
	 */
	public boolean ensureReady() {
		return ensureActive();
	}

	/**
	 * Publishes one body, opening the connection first when it is not up yet.
	 *
	 * @param routingKey the queue the broker should route to
	 * @return false when the broker is unreachable, so the caller can fall back
	 */
	public boolean publish(String routingKey, byte[] body) {
		if (!ensureActive()) {
			return false;
		}

		try {
			synchronized (lifecycleLock) {
				Channel currentPublishChannel = publishChannel;

				if (currentPublishChannel == null || !currentPublishChannel.isOpen()) {
					closeTransportLocked(false);
					return false;
				}

				currentPublishChannel.basicPublish(config.exchange(), routingKey, null, body);
			}

			return true;
		} catch (Exception exception) {
			handleTransportFailure("TX", exception);
			return false;
		}
	}

	/** Reports a failure seen by the caller's own send or receive path. */
	public void reportFailure(String direction, Exception exception) {
		handleTransportFailure(direction, exception);
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

		if (isConnectionUsable()) {
			return true;
		}

		if (System.currentTimeMillis() < nextRetryAtMillis) {
			return false;
		}

		synchronized (lifecycleLock) {
			if (isConnectionUsable()) {
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
				logger.warn("Không thể khởi tạo AMQP transport cho " + label + ". Dùng fallback plugin messaging trong "
					+ (RETRY_INTERVAL_MILLIS / 1000L) + "s tới. Lý do: " + exception.getMessage());
				return false;
			}
		}
	}

	private boolean isConnectionUsable() {
		Connection currentConnection = connection;
		Channel currentPublishChannel = publishChannel;

		return active
			&& currentConnection != null
			&& currentConnection.isOpen()
			&& currentPublishChannel != null
			&& currentPublishChannel.isOpen();
	}

	private void openTransportLocked(AmqpMessagingConfig currentConfig) throws Exception {
		closeTransportLocked(false);

		String queueName = endpoint.consumerQueue(currentConfig);

		ConnectionFactory factory = new ConnectionFactory();
		factory.setUri(currentConfig.uri());
		factory.setConnectionTimeout(currentConfig.connectionTimeoutMillis());
		factory.setRequestedHeartbeat(currentConfig.requestedHeartbeatSeconds());

		// luna reconnects on its own, on its own retry window; the client's
		// recovery would fight it and hide which side actually dropped
		factory.setAutomaticRecoveryEnabled(false);

		Connection nextConnection = factory.newConnection(endpoint.connectionName(currentConfig));
		Channel nextPublishChannel = nextConnection.createChannel();
		Channel nextConsumerChannel = nextConnection.createChannel();

		nextPublishChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.exchangeDeclare(currentConfig.exchange(), "direct", true);
		nextConsumerChannel.queueDeclare(queueName, true, false, false, null);
		nextConsumerChannel.queueBind(queueName, currentConfig.exchange(), queueName);

		DeliverCallback deliverCallback = (consumerTag, delivery) -> endpoint.onDelivery(delivery.getBody());
		CancelCallback cancelCallback = consumerTag -> logger.warn("AMQP consumer của " + label + " đã bị hủy: " + consumerTag);

		nextConsumerChannel.basicConsume(queueName, true, deliverCallback, cancelCallback);

		connection = nextConnection;
		publishChannel = nextPublishChannel;
		consumerChannel = nextConsumerChannel;
		active = true;
		nextRetryAtMillis = 0L;

		logger.success("Đã bật AMQP transport cho " + label
			+ " exchange=" + currentConfig.exchange()
			+ " queue=" + queueName
			+ " uri=" + currentConfig.maskedUri());
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
			// the transport is being torn down; a close that fails changes nothing
		}
	}
}
