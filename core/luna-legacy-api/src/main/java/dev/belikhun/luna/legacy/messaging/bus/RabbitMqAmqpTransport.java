package dev.belikhun.luna.legacy.messaging.bus;

import dev.belikhun.luna.legacy.heartbeat.BackendIdentity;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.AmqpConnection;
import dev.belikhun.luna.legacy.messaging.AmqpEndpoint;
import dev.belikhun.luna.legacy.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.legacy.messaging.AmqpPluginMessageEnvelope;
import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.UUID;

/**
 * A backend's side of the AMQP bus.
 *
 * The connection itself lives in {@link AmqpConnection}; what is left here is
 * the part that needs to know about players - naming its own queue, turning one
 * into an envelope, and handing an inbound body back on the server thread. All
 * three go through {@link PlayerBridge}, so this is the same class on every
 * platform rather than one per loader as the modern build has it.
 */
public final class RabbitMqAmqpTransport<P> implements AmqpTransport<P>, AmqpEndpoint {
	private final PluginMessagingBus<P> bus;
	private final PlayerBridge<P> players;
	private final BackendIdentity backendIdentity;
	private final boolean loggingEnabled;
	private final String platformLabel;
	private final LunaLogger logger;
	private final AmqpConnection connection;

	/**
	 * @param platformLabel what this backend calls itself in the connection name
	 *                      the broker shows, e.g. "forge-1.12.2"
	 */
	public RabbitMqAmqpTransport(
		PluginMessagingBus<P> bus,
		PlayerBridge<P> players,
		BackendIdentity backendIdentity,
		LunaLogger logger,
		boolean loggingEnabled,
		String platformLabel
	) {
		this.bus = bus;
		this.players = players;
		this.backendIdentity = backendIdentity;
		this.loggingEnabled = loggingEnabled;
		this.platformLabel = platformLabel;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.connection = new AmqpConnection(this, logger, platformLabel + " backend");
	}

	@Override
	public void updateConfig(AmqpMessagingConfig config) {
		connection.updateConfig(config);
	}

	@Override
	public boolean isActive() {
		return connection.isActive();
	}

	@Override
	public boolean send(P target, PluginMessageChannel channel, byte[] payload) {
		if (!connection.ensureReady()) {
			return false;
		}

		AmqpMessagingConfig currentConfig = connection.config();
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			resolveLocalServerName(currentConfig),
			players.idOf(target).toString(),
			players.nameOf(target),
			"",
			payload
		);

		if (!connection.publish(currentConfig.proxyQueue(), envelope.encode())) {
			return false;
		}

		if (loggingEnabled) {
			logger.audit("[TX:AMQP] backend->proxy channel=" + channel.value()
				+ " source=" + players.nameOf(target)
				+ " queue=" + currentConfig.proxyQueue()
				+ " bytes=" + payload.length);
		}

		return true;
	}

	@Override
	public void close() {
		connection.close();
	}

	@Override
	public String connectionName(AmqpMessagingConfig config) {
		return "luna-" + platformLabel + "-amqp-" + config.normalizeServerName(resolveLocalServerName(config));
	}

	@Override
	public String consumerQueue(AmqpMessagingConfig config) {
		return config.backendQueue(resolveLocalServerName(config));
	}

	/** Deliveries arrive on the client's own thread; listeners expect the server's. */
	@Override
	public void onDelivery(final byte[] body) {
		players.onServerThread(() -> dispatch(body));
	}

	/**
	 * The transport registers nothing per channel.
	 *
	 * Its queue is the backend's, not a channel's: every inbound body arrives on
	 * the one consumer and is routed by the envelope's channel field, so there is
	 * no per-channel subscription to open or close. The bus still calls these
	 * because a transport that *is* per channel would need them.
	 */
	@Override
	public void registerIncoming(PluginMessageChannel channel, InboundSink<P> sink) {
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
	}

	private void dispatch(byte[] body) {
		try {
			AmqpPluginMessageEnvelope envelope = AmqpPluginMessageEnvelope.decode(body);
			PluginMessageChannel channel = PluginMessageChannel.of(envelope.channel());
			P source = resolvePlayer(envelope.sourcePlayerId(), envelope.sourcePlayerName());
			PluginMessageDispatchResult result = bus.dispatchIncoming(source, channel, envelope.payload());

			if (loggingEnabled) {
				logger.audit("[RX:AMQP] proxy->backend channel=" + channel
					+ " source=" + (source == null ? "unknown" : players.nameOf(source))
					+ " bytes=" + envelope.payload().length
					+ " result=" + result.name());
			}

			logger.debug("[RX:AMQP] Đã xử lý channel=" + channel + " result=" + result.name());
		} catch (Exception exception) {
			logger.warn("Không thể xử lý AMQP payload trên " + platformLabel + ": " + exception.getMessage());
		}
	}

	private P resolvePlayer(String playerId, String playerName) {
		if (!Strings.isBlank(playerId)) {
			try {
				P byId = players.byId(UUID.fromString(playerId));

				if (byId != null) {
					return byId;
				}
			} catch (IllegalArgumentException ignored) {
				// a malformed id is still worth trying the name for
			}
		}

		if (!Strings.isBlank(playerName)) {
			return players.byName(playerName);
		}

		return null;
	}

	/** The proxy's name for this server wins, exactly as it does on Paper. */
	private String resolveLocalServerName(AmqpMessagingConfig currentConfig) {
		return currentConfig.effectiveLocalBackendMetadata(backendIdentity.current()).name();
	}
}
