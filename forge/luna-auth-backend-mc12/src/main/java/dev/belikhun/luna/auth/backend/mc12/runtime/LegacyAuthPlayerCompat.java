package dev.belikhun.luna.auth.backend.mc12.runtime;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;

/**
 * The four things the cage does to a player, in 1.12.2 terms.
 *
 * Each is a different call from the modern builds rather than a rename, which is
 * why they are gathered here instead of inlined: the action bar is a chat packet
 * with a type flag (there is no `sendActionBar`), and the lock effects take a
 * five-argument {@link PotionEffect} because the flags that hide the particles
 * and the HUD icon are constructor arguments on this line.
 */
public final class LegacyAuthPlayerCompat {
	/** Slowness 255: the player keeps their animation but covers no ground. */
	private static final int IMMOBILISE_AMPLIFIER = 255;

	private LegacyAuthPlayerCompat() {
	}

	/**
	 * Blind and immobilise, quietly.
	 *
	 * `ambient=false, particles=false, icon=false`: the point is to stop the
	 * player, not to decorate them, and a screen full of swirls during login is
	 * what every server with a login cage gets wrong.
	 */
	public static void applyLockEffects(EntityPlayerMP player, int blindnessTicks, int lockTicks) {
		player.addPotionEffect(new PotionEffect(MobEffects.BLINDNESS, blindnessTicks, 0, false, false));
		player.addPotionEffect(new PotionEffect(MobEffects.SLOWNESS, lockTicks, IMMOBILISE_AMPLIFIER, false, false));

		// jump boost 128 is the standard trick for pinning a player down: it cancels
		// the jump impulse outright rather than fighting it every tick
		player.addPotionEffect(new PotionEffect(MobEffects.JUMP_BOOST, lockTicks, 128, false, false));
	}

	public static void clearLockEffects(EntityPlayerMP player) {
		player.removePotionEffect(MobEffects.BLINDNESS);
		player.removePotionEffect(MobEffects.SLOWNESS);
		player.removePotionEffect(MobEffects.JUMP_BOOST);
	}

	/** The line above the hotbar; on this line it is a chat packet with a type. */
	public static void actionBar(EntityPlayerMP player, ITextComponent message) {
		if (player == null || player.connection == null || message == null) {
			return;
		}

		player.connection.sendPacket(new SPacketChat(message, ChatType.GAME_INFO));
	}

	/** Stop the player where they are, and tell the client about it. */
	public static void halt(EntityPlayerMP player) {
		player.motionX = 0D;
		player.motionY = 0D;
		player.motionZ = 0D;
		player.velocityChanged = true;
	}
}
