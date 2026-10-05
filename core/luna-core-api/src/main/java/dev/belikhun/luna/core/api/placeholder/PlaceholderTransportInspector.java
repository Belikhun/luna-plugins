package dev.belikhun.luna.core.api.placeholder;

import java.util.List;
import java.util.UUID;

/**
 * What a placeholder transport (the TAB bridge on a backend) can tell an
 * operator about itself, so the core's debug command can print it without
 * knowing which transport is installed.
 */
public interface PlaceholderTransportInspector {
	/** One-line-per-fact report for a player: readiness, requested ids, last values, queue. */
	List<String> describe(UUID playerId, String playerName);

	/** Counters for the whole transport. */
	List<String> summary();

	/**
	 * Logs every frame sent for a player for a while.
	 *
	 * @return false when the transport does not know the player
	 */
	boolean trace(UUID playerId, long seconds);
}
