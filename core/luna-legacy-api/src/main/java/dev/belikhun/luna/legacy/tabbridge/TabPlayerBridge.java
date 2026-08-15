package dev.belikhun.luna.legacy.tabbridge;

import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;

/**
 * The three things the TAB bridge needs from Minecraft that the messaging bus
 * does not already name.
 *
 * TAB tracks a player's world, game mode and invisibility because its own
 * conditions are written against them - `%world%` sorts a tab list, and a
 * vanished player has to leave it. Those three are the entire extra coupling:
 * measured against the modern build, everything else in the 1,240-line runtime
 * is bytes, maps and timers.
 *
 * It extends {@link PlayerBridge} rather than standing beside it because the
 * runtime needs both halves and there is exactly one implementation per
 * platform; two interfaces would mean two objects that could disagree about
 * which player they were describing.
 */
public interface TabPlayerBridge<P> extends PlayerBridge<P> {
	/**
	 * The world's name, as TAB's `%world%` conditions expect to match it.
	 *
	 * Never null: the runtime writes this straight into a packet, and TAB reads a
	 * missing world as a condition that can never match rather than as an error.
	 * Return `"unknown"` when the player is between worlds.
	 */
	String worldName(P player);

	/** Vanilla's game-mode ordinal: 0 survival, 1 creative, 2 adventure, 3 spectator. */
	int gameModeId(P player);

	/**
	 * Whether the player is invisible.
	 *
	 * The potion effect only. Vanish is a plugin's idea and arrives separately
	 * through {@link TabBridgePlayerStateSource}, because no two vanish plugins
	 * agree on what it means.
	 */
	boolean invisible(P player);
}
