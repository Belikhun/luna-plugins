package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

final class NoopNeoForgeTabBridgeRelationalPlaceholderSource implements NeoForgeTabBridgeRelationalPlaceholderSource {
	@Override
	public Map<String, Map<String, String>> resolve(ServerPlayer viewer) {
		return Map.of();
	}
}