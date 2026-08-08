package dev.belikhun.luna.core.api.messaging;

/**
 * What one side of the plugin-message bus contributes to its broker connection.
 *
 * Everything else about the connection is the same on every platform, so this is
 * deliberately the whole difference between the proxy's endpoint and a backend's:
 * which queue it consumes, what it calls itself, and what it does with a body.
 */
public interface AmqpEndpoint {
	/**
	 * Identifies this connection in the broker's management view.
	 *
	 * @param config the configuration the connection is being opened with
	 */
	String connectionName(AmqpMessagingConfig config);

	/**
	 * The queue this side declares, binds to the exchange and consumes.
	 *
	 * @param config the configuration the connection is being opened with
	 */
	String consumerQueue(AmqpMessagingConfig config);

	/**
	 * Hands over one delivered body.
	 *
	 * Called on the AMQP client's own thread, so an endpoint that needs the
	 * server thread has to hop there itself.
	 *
	 * @param body the raw envelope bytes
	 */
	void onDelivery(byte[] body);
}
