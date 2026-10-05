package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

public interface TabBridgePlayerStateSource {
	TabBridgePlayerState resolve(ServerPlayer player);
}