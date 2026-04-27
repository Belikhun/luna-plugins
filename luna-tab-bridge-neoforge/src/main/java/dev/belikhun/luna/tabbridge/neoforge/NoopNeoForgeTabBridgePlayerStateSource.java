package dev.belikhun.luna.tabbridge.neoforge.runtime;

import net.minecraft.server.level.ServerPlayer;

final class NoopNeoForgeTabBridgePlayerStateSource implements NeoForgeTabBridgePlayerStateSource {
	@Override
	public NeoForgeTabBridgePlayerState resolve(ServerPlayer player) {
		return NeoForgeTabBridgePlayerState.DEFAULT;
	}
}
