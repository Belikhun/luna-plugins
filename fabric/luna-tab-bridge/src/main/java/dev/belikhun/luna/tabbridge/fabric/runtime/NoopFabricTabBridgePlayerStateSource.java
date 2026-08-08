package dev.belikhun.luna.tabbridge.fabric.runtime;

import net.minecraft.server.level.ServerPlayer;

final class NoopFabricTabBridgePlayerStateSource implements FabricTabBridgePlayerStateSource {
	@Override
	public FabricTabBridgePlayerState resolve(ServerPlayer player) {
		return FabricTabBridgePlayerState.DEFAULT;
	}
}