package dev.belikhun.luna.tabbridge.fabric.runtime;

import net.minecraft.server.level.ServerPlayer;

public interface FabricTabBridgePlayerStateSource {
	FabricTabBridgePlayerState resolve(ServerPlayer player);
}