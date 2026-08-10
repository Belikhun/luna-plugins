package dev.belikhun.luna.core.mc.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Putting a line above the hotbar, as the 26.x line spells it: its own method
 * rather than a flag on the chat one.
 *
 * Every older line takes player-1x. See this module's README.
 */
public final class PlayerMessages {
	private PlayerMessages() {
	}

	public static void actionBar(ServerPlayer player, Component message) {
		player.sendOverlayMessage(message);
	}
}
