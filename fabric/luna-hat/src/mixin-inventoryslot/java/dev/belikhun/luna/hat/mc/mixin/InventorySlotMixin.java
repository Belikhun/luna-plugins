package dev.belikhun.luna.hat.mc.mixin;

import dev.belikhun.luna.hat.mc.runtime.HatHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Let the helmet slot take anything, when luna-hat says so - for 1.19 through
 * 1.20.4.
 *
 * These lines have no {@code ArmorSlot} class: the player's armor slots are
 * anonymous {@code Slot} subclasses built inside {@code InventoryMenu}, so there
 * is nothing to name as a mixin target. Injecting into anonymous inner classes
 * by their generated number ({@code InventoryMenu$1}) would break whenever
 * anything above them is reordered, so this widens {@code Slot.mayPlace} itself
 * and narrows the effect back down with the two checks below.
 *
 * The 1.21 and 26.x lines take mixin-armorslot, which targets the named class
 * directly. Both hand the decision to the same {@link HatHooks}.
 */
@Mixin(Slot.class)
public abstract class InventorySlotMixin {
	/**
	 * The player inventory index the head armor piece lives at.
	 *
	 * {@code InventoryMenu} builds its four armor slots as {@code 39 - k} over
	 * HEAD, CHEST, LEGS, FEET, so the helmet is 39. It has been that on every
	 * line this mixin serves.
	 */
	private static final int HEAD_SLOT = 39;

	@Shadow
	@Final
	public Container container;

	@Shadow
	public abstract int getContainerSlot();

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void lunaAllowAnyHat(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
		// every slot in the game reaches this, so leave immediately for all but
		// the one that matters
		if (getContainerSlot() != HEAD_SLOT || !(container instanceof Inventory inventory)) {
			return;
		}

		if (!(inventory.player instanceof ServerPlayer player)) {
			return;
		}

		if (HatHooks.allowInHelmetSlot(player, stack)) {
			callback.setReturnValue(true);
		}
	}
}
