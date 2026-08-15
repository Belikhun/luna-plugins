package dev.belikhun.luna.legacy.tabbridge;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The backend half of TAB's bridge: what TAB asked for, and what it is told.
 *
 * TAB drives the conversation. It registers the identifiers it wants and how
 * often, and the runtime answers them; nothing here decides what a tab list
 * looks like, only what this backend knows about the player rendering in it.
 */
public interface TabBridgeRuntime<P> {
	/** Send a payload on TAB's channel, queueing it when the player is not ready. */
	boolean sendRaw(P player, byte[] payload);

	/** Where an identifier TAB asked for but the snapshot does not carry is resolved. */
	void bindPlaceholderResolver(TabBridgePlaceholderResolver<P> placeholderResolver);

	void updatePlayerPlaceholders(P player, Map<String, String> placeholderValues);

	void updatePlayerRelationalPlaceholders(P player, Map<String, Map<String, String>> placeholderValues);

	/** Exactly what TAB registered, so the placeholder service resolves no more than that. */
	Set<String> requestedPlaceholderIdentifiers(UUID playerId);

	Map<String, String> placeholderValues(UUID playerId);

	/** The last payload TAB sent about this player, or null. For diagnostics only. */
	TabBridgePacket latestPacket(UUID playerId);

	void removePlayer(UUID playerId);

	void close();
}
