package dev.belikhun.luna.auth.backend.fabric.compat;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * Refusing "use the held item", as the 1.20-1.21 line's fabric-api spells it.
 *
 * Every other callback this mod registers has the same shape on both game lines;
 * this one does not, because {@code UseItemCallback} used to answer with an
 * {@code InteractionResultHolder} carrying the resulting stack and now answers
 * with a plain {@code InteractionResult}. The 26.x copy lives in
 * luna-auth-backend-mc26-fabric under the same name.
 */
public final class UseItemGate {
	private UseItemGate() {
	}

	/** Refuse the interaction whenever {@code allow} says the player may not. */
	public static void register(Predicate<Player> allow) {
		UseItemCallback.EVENT.register((player, level, hand) -> allow.test(player)
			? InteractionResultHolder.pass(player.getItemInHand(hand))
			: InteractionResultHolder.fail(player.getItemInHand(hand)));
	}
}
