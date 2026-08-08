package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

public final class NoopNeoForgeAmqpTransport implements NeoForgeAmqpTransport {
	private AmqpMessagingConfig config;

	public NoopNeoForgeAmqpTransport() {
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
