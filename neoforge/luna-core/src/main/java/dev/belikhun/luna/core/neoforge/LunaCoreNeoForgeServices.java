package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import net.minecraft.server.MinecraftServer;

public record LunaCoreNeoForgeServices(
	String modId,
	MinecraftServer server,
	DependencyManager dependencyManager,
	LunaLogger logger,
	BackendHeartbeatPublisher heartbeatPublisher
) {
}
