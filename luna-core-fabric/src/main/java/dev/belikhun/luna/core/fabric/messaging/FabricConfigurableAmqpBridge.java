package dev.belikhun.luna.core.fabric.messaging;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;

public interface FabricConfigurableAmqpBridge {
	void updateConfig(AmqpMessagingConfig config);

	void close();
}
