package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;

public final class RabbitMqNeoForgeAmqpClientProvider implements NeoForgeAmqpClientProvider {
	@Override
	public String name() {
		return "rabbitmq";
	}

	@Override
	public int priority() {
		return 100;
	}

	@Override
	public NeoForgeAmqpTransport create(NeoForgePluginMessagingBus bus, LunaLogger logger) {
		return new RabbitMqNeoForgeAmqpTransport(bus, logger);
	}
}
