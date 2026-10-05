package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

final class NoopTabBridgeRelationalPlaceholderSource implements TabBridgeRelationalPlaceholderSource {
	@Override
	public Map<String, Map<String, String>> resolve(ServerPlayer viewer) {
		return Map.of();
	}
}