package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;

public interface NeoForgeAmqpClientProvider {
	String name();

	int priority();

	NeoForgeAmqpTransport create(NeoForgePluginMessagingBus bus, LunaLogger logger);
}
