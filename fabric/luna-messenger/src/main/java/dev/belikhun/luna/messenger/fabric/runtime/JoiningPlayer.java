package dev.belikhun.luna.messenger.fabric.runtime;

import net.minecraft.server.level.ServerPlayer;

/**
 * The player vanilla is placing into the world right now.
 *
 * Vanilla broadcasts the "joined the game" line from inside
 * {@code PlayerList.placeNewPlayer}, before the player is in the player list and
 * before any fabric connection event has fired, so the line cannot be matched to
 * its player any other way. Placement runs on the server thread, one player at a
 * time, which is what makes a single slot enough.
 */
public final class JoiningPlayer {
	private static ServerPlayer current;

	private JoiningPlayer() {
	}

	public static void set(ServerPlayer player) {
		current = player;
	}

	public static void clear() {
		current = null;
	}

	public static ServerPlayer get() {
		return current;
	}
}
