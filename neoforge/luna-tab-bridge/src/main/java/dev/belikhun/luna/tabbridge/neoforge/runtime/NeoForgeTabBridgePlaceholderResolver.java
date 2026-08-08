package dev.belikhun.luna.tabbridge.neoforge.runtime;

import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface NeoForgeTabBridgePlaceholderResolver {
	String resolve(ServerPlayer player, String identifier);
}
