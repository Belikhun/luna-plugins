package dev.belikhun.luna.core.fabric.messaging;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyAdapterRegistry;
import dev.belikhun.luna.core.fabric.adapter.FabricPlatformMessagingBridge;

import java.util.function.Supplier;

public final class FabricPluginMessagingBus implements PluginMessageBus<FabricMessageSource, FabricMessageTarget> {

	private final FabricFamilyAdapterRegistry adapterRegistry;
	private final FabricFallbackPluginMessagingTransport fallbackTransport;
	private final FabricAmqpMessagingTransport amqpTransport;

	public FabricPluginMessagingBus(
		FabricFamilyAdapterRegistry adapterRegistry,
		Supplier<FabricVersionFamily> familySupplier,
		LunaLogger logger,
		boolean loggingEnabled
	) {
		this.adapterRegistry = adapterRegistry;
		this.fallbackTransport = new FabricFallbackPluginMessagingTransport(logger, loggingEnabled, adapterRegistry, familySupplier);
		this.amqpTransport = new FabricAmqpMessagingTransport(logger, loggingEnabled);
		this.amqpTransport.bindBridge(new RabbitMqFabricAmqpBridge(logger, loggingEnabled));
	}

	public void updateAmqpConfig(AmqpMessagingConfig config) {
		amqpTransport.updateConfig(config);
	}

	public boolean isAmqpActive() {
		return amqpTransport.isActive();
	}

	public void bindAmqpBridge(FabricAmqpBridge bridge) {
		amqpTransport.bindBridge(bridge);
	}

	public void bindFallbackPlatformBridge(FabricVersionFamily family, FabricPlatformMessagingBridge bridge) {
		adapterRegistry.get(family).ifPresent(adapter -> adapter.bindPlatformBridge(bridge));
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<FabricMessageSource> handler) {
		fallbackTransport.registerIncoming(channel, handler);
		amqpTransport.registerIncoming(channel, handler);
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		fallbackTransport.unregisterIncoming(channel);
		amqpTransport.unregisterIncoming(channel);
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		fallbackTransport.registerOutgoing(channel);
		amqpTransport.registerOutgoing(channel);
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		fallbackTransport.unregisterOutgoing(channel);
		amqpTransport.unregisterOutgoing(channel);
	}

	@Override
	public boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		if (amqpTransport.canSend(target) && amqpTransport.send(target, channel, payload)) {
			return true;
		}
		return fallbackTransport.send(target, channel, payload);
	}

	@Override
	public void close() {
		amqpTransport.close();
		fallbackTransport.close();
	}
}
