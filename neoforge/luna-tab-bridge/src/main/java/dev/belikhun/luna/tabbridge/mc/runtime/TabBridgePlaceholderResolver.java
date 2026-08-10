package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface TabBridgePlaceholderResolver {
	String resolve(ServerPlayer player, String identifier);
}
