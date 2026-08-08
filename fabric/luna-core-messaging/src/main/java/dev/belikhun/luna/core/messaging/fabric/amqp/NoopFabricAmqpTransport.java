package dev.belikhun.luna.core.messaging.fabric.amqp;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.server.level.ServerPlayer;

/** What the bus falls back to when no broker transport could be built. */
public final class NoopFabricAmqpTransport implements FabricAmqpTransport {
	@Override
	public void updateConfig(AmqpMessagingConfig config) {
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
	}
}
