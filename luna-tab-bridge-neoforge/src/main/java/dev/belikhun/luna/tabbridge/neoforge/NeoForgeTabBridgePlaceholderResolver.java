package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
interface NeoForgeTabBridgePlaceholderResolver {
	String resolve(ServerPlayer player, String identifier);
}
