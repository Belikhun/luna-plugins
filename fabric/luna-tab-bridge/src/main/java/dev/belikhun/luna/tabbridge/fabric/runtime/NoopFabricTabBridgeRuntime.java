package dev.belikhun.luna.tabbridge.fabric.runtime;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class NoopFabricTabBridgeRuntime implements FabricTabBridgeRuntime {
	@Override
	public boolean sendRaw(ServerPlayer player, byte[] payload) {
		return false;
	}

	@Override
	public void updatePlayerPlaceholders(ServerPlayer player, Map<String, String> placeholderValues) {
	}

	@Override
	public void updatePlayerRelationalPlaceholders(ServerPlayer player, Map<String, Map<String, String>> placeholderValues) {
	}

	@Override
	public Set<String> requestedPlaceholderIdentifiers(UUID playerId) {
		return Set.of();
	}

	@Override
	public Map<String, String> placeholderValues(UUID playerId) {
		return Map.of();
	}

	@Override
	public Optional<FabricTabBridgePacket> latestPacket(UUID playerId) {
		return Optional.empty();
	}

	@Override
	public void removePlayer(UUID playerId) {
	}

	@Override
	public void close() {
	}
}
