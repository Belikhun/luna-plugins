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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

	/** Channels whose listeners must not wait for the tick; see deliverOffTick. */
	private final Set<String> offTickChannels;

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
		this.offTickChannels = ConcurrentHashMap.newKeySet();
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

	/**
	 * Deliveries arrive on the broker's own thread; most listeners expect the
	 * server's, and the envelope has to be opened before we can know which.
	 *
	 * **A reply must never queue behind the thread that is waiting for it.** A
	 * request/response channel is answered while its caller blocks the server
	 * thread, so handing the reply to that same thread means it cannot run until
	 * the caller has already given up - the wait always burns its whole budget and
	 * then succeeds a tick later, whatever the budget is. Channels that opt out of
	 * the tick are dispatched here and now.
	 */
	@Override
	public void onDelivery(final byte[] body) {
		final AmqpPluginMessageEnvelope envelope = decode(body);

		if (envelope == null) {
			return;
		}

		if (offTickChannels.contains(envelope.channel())) {
			dispatch(envelope);

			return;
		}

		players.onServerThread(() -> dispatch(envelope));
	}

	@Override
	public void deliverOffTick(PluginMessageChannel channel) {
		if (channel != null) {
			offTickChannels.add(channel.value());
		}
	}

	private AmqpPluginMessageEnvelope decode(byte[] body) {
		try {
			return AmqpPluginMessageEnvelope.decode(body);
		} catch (Exception exception) {
			logger.warn("Không thể đọc AMQP payload trên " + platformLabel + ": " + exception.getMessage());

			return null;
		}
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
		if (channel != null) {
			offTickChannels.remove(channel.value());
		}
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
	}

	private void dispatch(AmqpPluginMessageEnvelope envelope) {
		try {
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
