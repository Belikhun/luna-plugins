package dev.belikhun.luna.auth.backend.mc.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * The three player-facing calls the auth lock makes that 26.x re-spelled, as the
 * 1.20-1.21 line has them.
 *
 * The bossbar took no id; the action bar was a flag on
 * {@code displayClientMessage}; and two of the three lock effects have since been
 * renamed. Everything else this mod touches is identical on both lines and is
 * called directly. The 26.x copy lives in luna-auth-backend-mc26-fabric under the
 * same name.
 */
public final class AuthPlayerCompat {
	private AuthPlayerCompat() {
	}

	/** A yellow progress bossbar carrying the prompt. */
	public static ServerBossEvent bossEvent(Component name) {
		return new ServerBossEvent(name, BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
	}

	/** Put a message above the hotbar. */
	public static void actionBar(ServerPlayer player, Component message) {
		player.displayClientMessage(message, true);
	}

	/** Blind and pin the player for as long as they stay unauthenticated. */
	public static void applyLockEffects(ServerPlayer player, int blindnessTicks, int lockTicks) {
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindnessTicks, 0, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, lockTicks, 10, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.JUMP, lockTicks, 128, false, false, false));
	}

	/** Give the player their movement back. */
	public static void clearLockEffects(ServerPlayer player) {
		player.removeEffect(MobEffects.BLINDNESS);
		player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
		player.removeEffect(MobEffects.JUMP);
	}
}
