package dev.belikhun.luna.auth.backend.fabric.compat;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * Refusing "use the held item", as the 26.x line's fabric-api spells it.
 *
 * The 1.20-1.21 copy lives in luna-auth-backend-fabric/src/mc21; see it for why
 * this one callback is the only one that needs a copy per line. Here the result
 * is a plain {@code InteractionResult}, so the held stack no longer travels back
 * with the answer.
 */
public final class UseItemGate {
	private UseItemGate() {
	}

	/** Refuse the interaction whenever {@code allow} says the player may not. */
	public static void register(Predicate<Player> allow) {
		UseItemCallback.EVENT.register((player, level, hand) -> allow.test(player)
			? InteractionResult.PASS
			: InteractionResult.FAIL);
	}
}
