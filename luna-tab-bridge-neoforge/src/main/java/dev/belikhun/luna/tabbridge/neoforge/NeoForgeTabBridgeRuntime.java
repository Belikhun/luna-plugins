package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface NeoForgeTabBridgeRuntime extends AutoCloseable {
	boolean sendRaw(ServerPlayer player, byte[] payload);

	void updatePlayerPlaceholders(ServerPlayer player, Map<String, String> placeholderValues);

	Map<String, String> placeholderValues(UUID playerId);

	Optional<NeoForgeTabBridgePacket> latestPacket(UUID playerId);

	void removePlayer(UUID playerId);

	@Override
	void close();
}
