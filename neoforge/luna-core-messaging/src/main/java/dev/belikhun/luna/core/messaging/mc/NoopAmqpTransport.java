package dev.belikhun.luna.core.messaging.mc;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

public final class NoopAmqpTransport implements AmqpTransport {
	private AmqpMessagingConfig config;

	public NoopAmqpTransport() {
		this.config = AmqpMessagingConfig.disabled();
	}

	@Override
	public void updateConfig(AmqpMessagingConfig config) {
		this.config = config == null ? AmqpMessagingConfig.disabled() : config.sanitize();
	}

	@Override
	public boolean isActive() {
		return false;
	}

	@Override
	public boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		return false;
	}

	@Override
	public void close() {
		config = AmqpMessagingConfig.disabled();
	}
}
