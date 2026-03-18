package dev.belikhun.luna.core.fabric.messaging;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpPluginMessageEnvelope;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class FabricAmqpMessagingTransport implements FabricMessagingTransport {

	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Map<PluginMessageChannel, PluginMessageHandler<FabricMessageSource>> incomingHandlers = new ConcurrentHashMap<>();
	private final Set<PluginMessageChannel> outgoingChannels = ConcurrentHashMap.newKeySet();
	private final AtomicReference<FabricAmqpBridge> bridgeRef = new AtomicReference<>();
	private volatile AmqpMessagingConfig config = AmqpMessagingConfig.disabled();

	public FabricAmqpMessagingTransport(LunaLogger logger, boolean loggingEnabled) {
		this.logger = logger.scope("FabricAmqp");
		this.loggingEnabled = loggingEnabled;
	}

	public void updateConfig(AmqpMessagingConfig config) {
		this.config = config == null ? AmqpMessagingConfig.disabled() : config.sanitize();
		FabricAmqpBridge bridge = bridgeRef.get();
		if (bridge instanceof FabricConfigurableAmqpBridge configurableBridge) {
			configurableBridge.updateConfig(this.config);
		}

		if (loggingEnabled) {
			logger.info("AMQP cấu hình đã cập nhật. enabled=" + this.config.enabled() + ", uri=" + this.config.maskedUri());
		}
	}

	public boolean isActive() {
		return config.isConfigured();
	}

	public void bindBridge(FabricAmqpBridge bridge) {
		FabricAmqpBridge previous = bridgeRef.getAndSet(bridge);
		if (previous != null) {
			previous.clearConsumer();
			if (previous instanceof FabricConfigurableAmqpBridge configurableBridge) {
				configurableBridge.close();
			}
		}

		if (bridge != null) {
			bridge.setConsumer(this::dispatchIncomingEnvelope);
			if (bridge instanceof FabricConfigurableAmqpBridge configurableBridge) {
				configurableBridge.updateConfig(config);
			}
		}
	}

	public boolean canSend(FabricMessageTarget target) {
		if (!isActive()) {
			return false;
		}

		if (target == null) {
			return false;
		}

		String serverName = target.serverName() == null ? "" : target.serverName().trim();
		return !serverName.isBlank() || target.playerId() != null;
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<FabricMessageSource> handler) {
		incomingHandlers.put(channel, handler);
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		incomingHandlers.remove(channel);
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		outgoingChannels.add(channel);
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		outgoingChannels.remove(channel);
	}

	@Override
	public boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		if (!canSend(target) || !outgoingChannels.contains(channel)) {
			return false;
		}

		FabricAmqpBridge bridge = bridgeRef.get();
		if (bridge == null) {
			return false;
		}

		String targetServerName = target.serverName() == null ? "" : target.serverName().trim();
		String sourceServerName = config.effectiveLocalServerName("fabric-backend");
		AmqpPluginMessageEnvelope envelope = new AmqpPluginMessageEnvelope(
			AmqpPluginMessageEnvelope.CURRENT_PROTOCOL,
			channel.value(),
			sourceServerName,
			"",
			"",
			targetServerName,
			payload
		);

		String routingKey = targetServerName.isBlank()
			? config.proxyQueue()
			: config.backendQueue(targetServerName);

		if (loggingEnabled) {
			logger.debug("[AMQP] Publish " + channel + " routingKey=" + routingKey + " target=" + target);
		}

		return bridge.publish(routingKey, envelope.encode());
	}

	public void dispatchIncomingEnvelope(byte[] envelopePayload) {
		try {
			AmqpPluginMessageEnvelope envelope = AmqpPluginMessageEnvelope.decode(envelopePayload);
			PluginMessageChannel channel = PluginMessageChannel.of(envelope.channel());
			FabricMessageSource source = new FabricMessageSource(
				envelope.sourceServerName(),
				envelope.sourcePlayerId() == null || envelope.sourcePlayerId().isBlank() ? null : java.util.UUID.fromString(envelope.sourcePlayerId()),
				envelope.sourcePlayerName()
			);
			dispatchIncoming(source, channel, envelope.payload());
		} catch (Exception exception) {
			logger.warn("Bỏ qua AMQP envelope không hợp lệ: " + exception.getMessage());
		}
	}

	public void dispatchIncoming(FabricMessageSource source, PluginMessageChannel channel, byte[] payload) {
		PluginMessageHandler<FabricMessageSource> handler = incomingHandlers.get(channel);
		if (handler != null) {
			handler.handle(new PluginMessageContext<>(channel, source, payload));
		}
	}

	@Override
	public void close() {
		FabricAmqpBridge bridge = bridgeRef.getAndSet(null);
		if (bridge != null) {
			bridge.clearConsumer();
			if (bridge instanceof FabricConfigurableAmqpBridge configurableBridge) {
				configurableBridge.close();
			}
		}
		incomingHandlers.clear();
		outgoingChannels.clear();
	}
}
