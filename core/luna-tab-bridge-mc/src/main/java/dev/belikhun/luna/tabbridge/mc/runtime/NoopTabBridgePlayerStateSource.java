package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

final class NoopTabBridgePlayerStateSource implements TabBridgePlayerStateSource {
	@Override
	public TabBridgePlayerState resolve(ServerPlayer player) {
		return TabBridgePlayerState.DEFAULT;
	}
}