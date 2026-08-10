package dev.belikhun.luna.auth.backend.fabric.mixin;

import dev.belikhun.luna.auth.backend.mc.runtime.AuthLockHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keep a player who has not authenticated yet from throwing items away.
 *
 * NeoForge has {@code ItemTossEvent}; Fabric API has none, so the drop itself is
 * intercepted. The stack has already been taken out of the inventory by the time
 * this runs, so refusing means putting it back - which is exactly what the
 * NeoForge handler does when it cancels the toss.
 */
@Mixin(Player.class)
public abstract class PlayerDropMixin {
	@Inject(
		method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void luna$refuseUntilAuthenticated(
		ItemStack stack,
		boolean dropAround,
		boolean includeThrowerName,
		CallbackInfoReturnable<ItemEntity> callback
	) {
		if (!((Object) this instanceof ServerPlayer player) || !AuthLockHooks.refuseDrop(player, stack)) {
			return;
		}

		AuthLockHooks.restoreTossedItem(player, stack);
		callback.setReturnValue(null);
	}
}
