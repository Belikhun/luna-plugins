package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class NoopNeoForgeTabBridgeRuntime implements NeoForgeTabBridgeRuntime {
	@Override
	public boolean sendRaw(ServerPlayer player, byte[] payload) {
		return false;
	}

	@Override
	public void updatePlayerPlaceholders(ServerPlayer player, Map<String, String> placeholderValues) {
	}

	@Override
	public Map<String, String> placeholderValues(UUID playerId) {
		return Map.of();
	}

	@Override
	public Optional<NeoForgeTabBridgePacket> latestPacket(UUID playerId) {
		return Optional.empty();
	}

	@Override
	public void removePlayer(UUID playerId) {
	}

	@Override
	public void close() {
	}
}
