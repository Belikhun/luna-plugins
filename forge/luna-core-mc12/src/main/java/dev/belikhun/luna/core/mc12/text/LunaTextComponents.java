package dev.belikhun.luna.core.mc12.text;

import dev.belikhun.luna.legacy.text.LegacyText;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/**
 * MiniMessage into something a 1.12.2 client will render.
 *
 * The modern platforms build a component tree and keep its structure; here the
 * message is flattened to a `§`-coded string and wrapped in a single
 * `TextComponentString`. That is not laziness - 1.12.2's own chat pipeline reads
 * legacy codes out of a string component, and it is what every mod of the era does.
 *
 * The name matches the modern platforms' `LunaTextComponents` on purpose, so a call
 * site reads the same across four loaders even though nothing is shared.
 */
public final class LunaTextComponents {
	private LunaTextComponents() {
	}

	/** Render one of luna's MiniMessage strings. */
	public static ITextComponent mini(String miniMessage) {
		return new TextComponentString(LegacyText.legacy(miniMessage));
	}

	/** Send a MiniMessage string to a player's chat. */
	public static void send(EntityPlayerMP player, String miniMessage) {
		if (player == null) {
			return;
		}

		player.sendMessage(mini(miniMessage));
	}

	/**
	 * Send to a player's action bar.
	 *
	 * 1.12.2 has no `sendStatusMessage` overload that takes a chat type, so the
	 * action bar is reached by the boolean the method already has - `true` meaning
	 * "above the hotbar" rather than "in chat".
	 */
	public static void actionBar(EntityPlayerMP player, String miniMessage) {
		if (player == null) {
			return;
		}

		player.sendStatusMessage(mini(miniMessage), true);
	}
}
