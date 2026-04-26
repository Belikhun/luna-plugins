package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.minecraft.server.MinecraftServer;

public record LunaCoreNeoForgeServices(
	String modId,
	MinecraftServer server,
	DependencyManager dependencyManager,
	LunaLogger logger
) {
}
