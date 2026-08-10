package dev.belikhun.luna.auth.backend.mc.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.UUID;

/**
 * The three player-facing calls the auth lock makes that 26.x re-spelled, as this
 * line has them.
 *
 * A bossbar now carries its own id, the action bar has its own method rather than
 * a flag on the chat one, and two of the three lock effects took their in-game
 * names: {@code MOVEMENT_SLOWDOWN} is {@code SLOWNESS} and {@code JUMP} is
 * {@code JUMP_BOOST}. The 1.20-1.21 copy lives in
 * luna-auth-backend-fabric/src/mc21.
 */
public final class AuthPlayerCompat {
	private AuthPlayerCompat() {
	}

	/** A yellow progress bossbar carrying the prompt. */
	public static ServerBossEvent bossEvent(Component name) {
		return new ServerBossEvent(UUID.randomUUID(), name, BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
	}

	/** Put a message above the hotbar. */
	public static void actionBar(ServerPlayer player, Component message) {
		player.sendOverlayMessage(message);
	}

	/** Blind and pin the player for as long as they stay unauthenticated. */
	public static void applyLockEffects(ServerPlayer player, int blindnessTicks, int lockTicks) {
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindnessTicks, 0, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, lockTicks, 10, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, lockTicks, 128, false, false, false));
	}

	/** Give the player their movement back. */
	public static void clearLockEffects(ServerPlayer player) {
		player.removeEffect(MobEffects.BLINDNESS);
		player.removeEffect(MobEffects.SLOWNESS);
		player.removeEffect(MobEffects.JUMP_BOOST);
	}
}
