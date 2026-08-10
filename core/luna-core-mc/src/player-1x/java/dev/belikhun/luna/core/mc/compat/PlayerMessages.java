package dev.belikhun.luna.core.mc.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Putting a line above the hotbar, as the 1.19-1.21 line spells it: a flag on
 * the chat method.
 *
 * 26.x gave the action bar its own method and takes player-26. See this
 * module's README for how a platform composes its compat set.
 */
public final class PlayerMessages {
	private PlayerMessages() {
	}

	public static void actionBar(ServerPlayer player, Component message) {
		player.displayClientMessage(message, true);
	}
}
