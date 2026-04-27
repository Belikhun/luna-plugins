package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

public interface NeoForgeTabBridgePlayerStateSource {
	NeoForgeTabBridgePlayerState resolve(ServerPlayer player);
}