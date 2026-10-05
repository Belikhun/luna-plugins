package dev.belikhun.luna.tabbridge.mc.runtime;

import dev.belikhun.luna.core.api.placeholder.PlaceholderTransportInspector;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * This backend's half of TAB's bridge protocol.
 *
 * TAB on the proxy asks; the runtime answers over {@code tab:bridge-6}. The
 * updater calls {@link #refresh} on a cadence, and the runtime decides what has
 * changed and is due, so one call per player per tick is the whole contract.
 */
public interface TabBridgeRuntime extends AutoCloseable, PlaceholderTransportInspector {
	/** Sends a raw frame, queueing it while TAB has not yet said hello for the player. */
	boolean sendRaw(ServerPlayer player, byte[] payload);

	/** Re-evaluates a player's requested placeholders, relational values and state. */
	void refresh(ServerPlayer player);

	/** The identifiers TAB asked this backend to keep current for a player. */
	Set<String> requestedPlaceholderIdentifiers(UUID playerId);

	/** The last frame TAB sent for a player. */
	Optional<TabBridgePacket> latestPacket(UUID playerId);

	/** Forgets a player, on logout. */
	void removePlayer(UUID playerId);

	@Override
	void close();
}
