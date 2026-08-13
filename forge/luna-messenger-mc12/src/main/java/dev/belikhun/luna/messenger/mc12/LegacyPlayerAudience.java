package dev.belikhun.luna.messenger.mc12;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.messenger.runtime.PlayerAudience;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;

/**
 * Putting text on a 1.12.2 player's screen.
 *
 * The MiniMessage half goes through the core's renderer, which downsamples RGB to
 * the sixteen legacy colours - 1.12.2 has no hex chat, so a gradient the proxy
 * wrote arrives as the nearest named colour rather than not at all.
 *
 * The plain half deliberately does not go near that renderer. Text luna did not
 * author must not be parsed as markup, and a `<` in a player's name is exactly the
 * kind of thing that would otherwise be read as a tag.
 */
public final class LegacyPlayerAudience implements PlayerAudience<EntityPlayerMP> {
	@Override
	public void sendMini(EntityPlayerMP player, String miniMessage) {
		if (player == null) {
			return;
		}

		player.sendMessage(LunaTextComponents.mini(miniMessage == null ? "" : miniMessage));
	}

	@Override
	public void sendPlain(EntityPlayerMP player, String text) {
		if (player == null) {
			return;
		}

		player.sendMessage(new TextComponentString(text == null ? "" : text));
	}
}
