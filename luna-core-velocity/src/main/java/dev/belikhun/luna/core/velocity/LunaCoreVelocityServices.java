package dev.belikhun.luna.core.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;

public record LunaCoreVelocityServices(
	LunaCoreVelocityPlugin plugin,
	ProxyServer proxyServer,
	LunaLogger logger,
	DependencyManager dependencyManager,
	VelocityHttpServerManager httpServerManager,
	VelocityPluginMessagingBus pluginMessagingBus,
	BackendStatusView backendStatusView
) {
}
