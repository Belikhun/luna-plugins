package dev.belikhun.luna.core.paper.messaging;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpConnection;
import dev.belikhun.luna.core.api.messaging.AmqpEndpoint;
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

/**
 * The Paper backend's side of the AMQP bus.
 *
 * The connection itself lives in {@link AmqpConnection}; what is left here is
 * the channel registry, the audit trail and the part only this platform can do -
 * naming its own queue, turning a player into an envelope, and handing an inbound
 * body to the server thread.
 */
final class PaperAmqpMessagingTransport implements AmqpEndpoint {
	private final Plugin plugin;
	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Supplier<BackendMetadata> localBackendMetadataSupplier;
	private final Map<String, PluginMessageHandler<Player>> incomingHandlers;
	private final Set<String> outgoingChannels;
	private final AmqpConnection connection;

	PaperAmqpMessagingTransport(Plugin plugin, LunaLogger logger, boolean loggingEnabled, Supplier<BackendMetadata> localBackendMetadataSupplier) {
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging").scope("AMQP");
		this.loggingEnabled = loggingEnabled;
		this.localBackendMetadataSupplier = localBackendMetadataSupplier;
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
		this.connection = new AmqpConnection(this, logger, "backend");
	}

	void updateConfig(AmqpMessagingConfig nextConfig) {
		connection.updateConfig(nextConfig);
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

		if (!connection.ensureReady()) {
			return false;
		}

		AmqpMessagingConfig currentConfig = connection.config();
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			localServerName(currentConfig),
			target.getUniqueId().toString(),
			target.getName(),
			"",
			payload
		);

		if (!connection.publish(currentConfig.proxyQueue(), envelope.encode())) {
			return false;
		}

		if (loggingEnabled) {
			logger.audit("[TX:AMQP] backend->proxy channel=" + channel.value()
				+ " source=" + target.getName()
				+ " queue=" + currentConfig.proxyQueue()
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
		return "luna-paper-amqp-" + config.normalizeServerName(localServerName(config));
	}

	@Override
	public String consumerQueue(AmqpMessagingConfig config) {
		return config.backendQueue(localServerName(config));
	}

	/** Deliveries arrive on the client's own thread; handlers expect the server's. */
	@Override
	public void onDelivery(byte[] body) {
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
				// a malformed id is still worth trying the name for
			}
		}

		if (playerName != null && !playerName.isBlank()) {
			return plugin.getServer().getPlayer(playerName);
		}

		return null;
	}

	private String localServerName(AmqpMessagingConfig currentConfig) {
		return currentConfig.effectiveLocalBackendMetadata(localBackendMetadataSupplier.get()).name();
	}
}
