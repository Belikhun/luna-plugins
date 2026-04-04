package dev.belikhun.luna.core.fabric;

import net.minecraft.server.MinecraftServer;

public interface FabricFamilyBootstrap {

	FabricVersionFamily family();

	void register(LunaCoreFabricRuntime runtime);

	MinecraftServer server();
}
