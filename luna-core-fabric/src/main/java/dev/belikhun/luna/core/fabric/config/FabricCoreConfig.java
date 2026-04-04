package dev.belikhun.luna.core.fabric.config;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;

import java.io.IOException;
import java.nio.file.Path;

public record FabricCoreConfig(
	boolean debugLogging,
	boolean messagingDebugLogging,
	AmqpMessagingConfig amqp
) {
	public static FabricCoreConfig load(Path path) throws IOException {
		SimpleTomlConfig config = SimpleTomlConfig.load(path);
		return new FabricCoreConfig(
			config.getBoolean("debugLogging", false),
			config.getBoolean("messagingDebugLogging", false),
			new AmqpMessagingConfig(
				config.getBoolean("amqp.enabled", false),
				config.getString("amqp.uri", ""),
				config.getString("amqp.exchange", "luna.network"),
				config.getString("amqp.proxyQueue", "luna.proxy"),
				config.getString("amqp.backendQueuePrefix", "backend."),
				config.getString("amqp.localServerName", "fabric-backend"),
				config.getInt("amqp.connectionTimeoutMillis", 5000),
				config.getInt("amqp.requestedHeartbeatSeconds", 15)
			).sanitize()
		);
	}

	public static String defaultToml() {
		return "# Luna Core Fabric configuration\n"
			+ "debugLogging = false\n"
			+ "messagingDebugLogging = false\n\n"
			+ "[amqp]\n"
			+ "enabled = false\n"
			+ "uri = \"\"\n"
			+ "exchange = \"luna.network\"\n"
			+ "proxyQueue = \"luna.proxy\"\n"
			+ "backendQueuePrefix = \"backend.\"\n"
			+ "localServerName = \"fabric-backend\"\n"
			+ "connectionTimeoutMillis = 5000\n"
			+ "requestedHeartbeatSeconds = 15\n";
	}
}
