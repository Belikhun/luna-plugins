package dev.belikhun.luna.core.messaging.fabric.amqp;

import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpConnection;
import dev.belikhun.luna.core.api.messaging.AmqpEndpoint;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpPluginMessageEnvelope;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * The Fabric backend's side of the AMQP bus.
 *
 * The connection itself lives in {@link AmqpConnection}; what is left here is
 * the part only this platform can do - naming its own queue, turning a player
 * into an envelope, and handing an inbound body to the server thread.
 */
public final class RabbitMqFabricAmqpTransport implements FabricAmqpTransport, AmqpEndpoint {
	private final IncomingMessageSink sink;
	private final BackendIdentity backendIdentity;
	private final boolean loggingEnabled;
	private final LunaLogger logger;
	private final AmqpConnection connection;
	private final Set<String> asyncChannels;

	public RabbitMqFabricAmqpTransport(IncomingMessageSink sink, BackendIdentity backendIdentity, LunaLogger logger, boolean loggingEnabled) {
		this.sink = sink;
		this.asyncChannels = ConcurrentHashMap.newKeySet();
		this.backendIdentity = backendIdentity;
		this.loggingEnabled = loggingEnabled;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.connection = new AmqpConnection(this, logger, "Fabric backend");
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
	public boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		if (!connection.ensureReady()) {
			return false;
		}

		AmqpMessagingConfig currentConfig = connection.config();
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			resolveLocalServerName(currentConfig),
			target == null ? "" : target.getUUID().toString(),
			target == null ? "" : target.getScoreboardName(),
			"",
			payload
		);

		if (!connection.publish(currentConfig.proxyQueue(), envelope.encode())) {
			return false;
		}

		if (loggingEnabled) {
			logger.audit("[TX:AMQP] backend->proxy channel=" + channel.value()
				+ " source=" + (target == null ? "<server>" : target.getScoreboardName())
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
		return "luna-fabric-amqp-" + config.normalizeServerName(resolveLocalServerName(config));
	}

	@Override
	public String consumerQueue(AmqpMessagingConfig config) {
		return config.backendQueue(resolveLocalServerName(config));
	}

	/** Deliveries arrive on the client's own thread; listeners expect the server's. */
	@Override
	public void onDelivery(byte[] body) {
		AmqpPluginMessageEnvelope envelope;

		try {
			envelope = AmqpPluginMessageEnvelope.decode(body);
		} catch (RuntimeException exception) {
			logger.warn("Không thể đọc AMQP payload: " + exception.getMessage());
			return;
		}

		if (asyncChannels.contains(envelope.channel())) {
			dispatch(envelope);
			return;
		}

		MinecraftServer server = LunaCoreFabric.services().server();

		server.execute(() -> dispatch(envelope));
	}

	@Override
	public void allowAsyncDelivery(PluginMessageChannel channel) {
		asyncChannels.add(channel.value());
	}

	private void dispatch(AmqpPluginMessageEnvelope envelope) {
		try {
			PluginMessageChannel channel = PluginMessageChannel.of(envelope.channel());
			ServerPlayer source = resolvePlayer(envelope.sourcePlayerId(), envelope.sourcePlayerName());
			PluginMessageDispatchResult result = sink.dispatch(source, channel, envelope.payload());

			if (loggingEnabled) {
				logger.audit("[RX:AMQP] proxy->backend channel=" + channel
					+ " source=" + (source == null ? "unknown" : source.getScoreboardName())
					+ " bytes=" + envelope.payload().length
					+ " result=" + result.name());
			}

			logger.debug("[RX:AMQP] Đã xử lý channel=" + channel + " result=" + result.name());
		} catch (Exception exception) {
			logger.warn("Không thể xử lý AMQP payload trên Fabric: " + exception.getMessage());
		}
	}

	private ServerPlayer resolvePlayer(String playerId, String playerName) {
		MinecraftServer server = LunaCoreFabric.services().server();

		if (playerId != null && !playerId.isBlank()) {
			try {
				return server.getPlayerList().getPlayer(UUID.fromString(playerId));
			} catch (IllegalArgumentException ignored) {
				// a malformed id is still worth trying the name for
			}
		}

		if (playerName != null && !playerName.isBlank()) {
			return server.getPlayerList().getPlayerByName(playerName);
		}

		return null;
	}

	/** The proxy's name for this server wins, exactly as it does on Paper. */
	private String resolveLocalServerName(AmqpMessagingConfig currentConfig) {
		return currentConfig.effectiveLocalBackendMetadata(backendIdentity.current()).name();
	}
}
