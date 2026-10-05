package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The runtime used when there is no bus to reach the proxy over. */
final class NoopTabBridgeRuntime implements TabBridgeRuntime {
	@Override
	public boolean sendRaw(ServerPlayer player, byte[] payload) {
		return false;
	}

	@Override
	public void refresh(ServerPlayer player) {
	}

	@Override
	public Set<String> requestedPlaceholderIdentifiers(UUID playerId) {
		return Set.of();
	}

	@Override
	public Optional<TabBridgePacket> latestPacket(UUID playerId) {
		return Optional.empty();
	}

	@Override
	public void removePlayer(UUID playerId) {
	}

	@Override
	public List<String> describe(UUID playerId, String playerName) {
		return List.of("  (tab bridge is a no-op on this server: no plugin message bus)");
	}

	@Override
	public List<String> summary() {
		return List.of("  (tab bridge is a no-op on this server: no plugin message bus)");
	}

	@Override
	public boolean trace(UUID playerId, long seconds) {
		return false;
	}

	@Override
	public void close() {
	}
}
