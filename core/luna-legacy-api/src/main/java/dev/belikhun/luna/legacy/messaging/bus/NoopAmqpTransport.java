package dev.belikhun.luna.legacy.messaging.bus;

import dev.belikhun.luna.legacy.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;

/**
 * What the bus falls back to when the AMQP client could not be constructed.
 *
 * It answers false rather than throwing, so a broker that is missing or
 * misconfigured degrades the backend to "no cross-server messaging" instead of
 * taking the server down with it.
 */
public final class NoopAmqpTransport<P> implements AmqpTransport<P> {
	private AmqpMessagingConfig config = AmqpMessagingConfig.disabled();

	@Override
	public void updateConfig(AmqpMessagingConfig config) {
		this.config = config == null ? AmqpMessagingConfig.disabled() : config.sanitize();
	}

	@Override
	public boolean isActive() {
		return false;
	}

	@Override
	public boolean send(P target, PluginMessageChannel channel, byte[] payload) {
		return false;
	}

	@Override
	public void close() {
		config = AmqpMessagingConfig.disabled();
	}

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
}
