package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TabBridgeRuntime extends AutoCloseable {
	boolean sendRaw(ServerPlayer player, byte[] payload);

	default void bindPlaceholderResolver(TabBridgePlaceholderResolver placeholderResolver) {
	}

	void updatePlayerPlaceholders(ServerPlayer player, Map<String, String> placeholderValues);

	void updatePlayerRelationalPlaceholders(ServerPlayer player, Map<String, Map<String, String>> placeholderValues);

	Set<String> requestedPlaceholderIdentifiers(UUID playerId);

	Map<String, String> placeholderValues(UUID playerId);

	Optional<TabBridgePacket> latestPacket(UUID playerId);

	void removePlayer(UUID playerId);

	@Override
	void close();
}
