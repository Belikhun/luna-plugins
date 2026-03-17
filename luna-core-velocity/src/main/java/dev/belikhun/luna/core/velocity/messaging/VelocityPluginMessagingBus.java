package dev.belikhun.luna.core.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;

public final class VelocityPluginMessagingBus implements PluginMessageBus<Object, Object> {
	private final VelocityBungeePluginMessagingBus fallbackBus;
	private final VelocityAmqpMessagingTransport amqpTransport;

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger) {
		this(proxyServer, plugin, logger, false);
	}

	public VelocityPluginMessagingBus(ProxyServer proxyServer, Object plugin, LunaLogger logger, boolean loggingEnabled) {
		this.fallbackBus = new VelocityBungeePluginMessagingBus(proxyServer, plugin, logger, loggingEnabled);
		this.amqpTransport = new VelocityAmqpMessagingTransport(proxyServer, plugin, logger, loggingEnabled);
	}

	public void updateAmqpConfig(AmqpMessagingConfig config) {
		amqpTransport.updateConfig(config);
	}

	public boolean isAmqpActive() {
		return amqpTransport.isActive();
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Object> handler) {
		fallbackBus.registerIncoming(channel, handler);
		amqpTransport.registerIncoming(channel, handler);
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		fallbackBus.unregisterIncoming(channel);
		amqpTransport.unregisterIncoming(channel);
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		fallbackBus.registerOutgoing(channel);
		amqpTransport.registerOutgoing(channel);
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		fallbackBus.unregisterOutgoing(channel);
		amqpTransport.unregisterOutgoing(channel);
	}

	@Override
	public boolean send(Object target, PluginMessageChannel channel, byte[] payload) {
		if (amqpTransport.canSend(target) && amqpTransport.send(target, channel, payload)) {
			return true;
		}
		return fallbackBus.send(target, channel, payload);
	}

	@Override
	public void close() {
		amqpTransport.close();
		fallbackBus.close();
	}
}
