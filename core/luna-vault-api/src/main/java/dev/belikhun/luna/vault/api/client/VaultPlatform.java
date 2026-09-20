package dev.belikhun.luna.vault.api.client;

import java.util.Collection;
import java.util.UUID;

/**
 * Everything the backend client needs from the game server, and nothing else.
 *
 * The client's requests ride a player's connection to the proxy, so it has to
 * pick one who is online; the four operations here are what that takes. Naming
 * them keeps the client itself free of any one loader's classes, which is why
 * one copy of it serves Paper, Fabric, NeoForge and Forge.
 *
 * @param <P> the loader's online-player type
 */
public interface VaultPlatform<P> {
	/** The player's account id. */
	UUID idOf(P player);

	/** The player's current name. */
	String nameOf(P player);

	/** The online player with this id, or null. */
	P byId(UUID playerId);

	/** Everyone currently online. */
	Collection<? extends P> online();
}
