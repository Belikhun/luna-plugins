package dev.belikhun.luna.core.api.placeholder;

import java.util.UUID;

/**
 * What a platform gives the engine: identity for its players, samples of its
 * world, and a way to ask a foreign placeholder system.
 *
 * The sampling calls run on the server thread, on the platform's own cadence.
 * Nothing else in the engine touches the game, which is what lets a resolver
 * run on TAB's thread, the bridge's thread or an HTTP handler.
 *
 * @param <P> the platform's player type
 */
public interface PlaceholderHost<P> {
	/** The player's UUID. */
	UUID idOf(P player);

	/** The player's account name. */
	String nameOf(P player);

	/** Takes the server sample. Called on the server thread. */
	ServerSample sampleServer();

	/** Takes one player's sample. Called on the server thread. */
	PlayerSample samplePlayer(P player);

	/**
	 * Asks whatever other placeholder system the platform has, for a ref no luna
	 * spec claims: PlaceholderAPI on Paper, nothing on the mod loaders.
	 *
	 * @return the value, or null when nothing answers
	 */
	default String foreign(P player, PlaceholderRef ref) {
		return null;
	}
}
