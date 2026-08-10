package dev.belikhun.luna.auth.backend.fabric.mixin;

import dev.belikhun.luna.auth.backend.mc.runtime.AuthLockHooks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep a player who has not authenticated yet from picking anything up.
 *
 * NeoForge has {@code ItemEntityPickupEvent}; Fabric API has no pickup event, so
 * the touch that would collect the stack is intercepted instead. Cancelling here
 * leaves the item on the ground exactly as it was.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void luna$refuseUntilAuthenticated(Player player, CallbackInfo callback) {
		if (AuthLockHooks.isLocked(player)) {
			callback.cancel();
		}
	}
}
