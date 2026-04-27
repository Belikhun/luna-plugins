package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

final class NoopNeoForgeTabBridgePlayerStateSource implements NeoForgeTabBridgePlayerStateSource {
	@Override
	public NeoForgeTabBridgePlayerState resolve(ServerPlayer player) {
		return NeoForgeTabBridgePlayerState.DEFAULT;
	}
}