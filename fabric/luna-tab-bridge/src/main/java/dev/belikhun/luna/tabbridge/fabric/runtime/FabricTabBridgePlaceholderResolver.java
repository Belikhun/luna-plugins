package dev.belikhun.luna.tabbridge.fabric.runtime;

import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface FabricTabBridgePlaceholderResolver {
	String resolve(ServerPlayer player, String identifier);
}
