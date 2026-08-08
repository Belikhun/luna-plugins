package dev.belikhun.luna.core.fabric;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.minecraft.server.MinecraftServer;

public record LunaCoreFabricServices(
	String modId,
	MinecraftServer server,
	DependencyManager dependencyManager,
	LunaLogger logger,
	BackendHeartbeatPublisher heartbeatPublisher
) {
}
