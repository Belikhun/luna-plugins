package dev.belikhun.luna.messenger.fabric.mixin;

import dev.belikhun.luna.messenger.fabric.runtime.JoiningPlayer;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remember who is being placed, so the join line vanilla broadcasts during
 * placement can be told apart: a real player's is refused (the proxy announces
 * it), a simulated one's is kept.
 *
 * Not required: if a game line spells this method differently, the only loss is
 * that a simulated player's join line is refused along with everyone else's.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@Inject(method = "placeNewPlayer", at = @At("HEAD"), require = 0)
	private void luna$rememberJoining(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callback) {
		JoiningPlayer.set(player);
	}

	@Inject(method = "placeNewPlayer", at = @At("RETURN"), require = 0)
	private void luna$forgetJoining(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callback) {
		JoiningPlayer.clear();
	}
}
