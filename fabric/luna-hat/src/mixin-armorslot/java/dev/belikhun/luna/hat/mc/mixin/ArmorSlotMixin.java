package dev.belikhun.luna.hat.mc.mixin;

import dev.belikhun.luna.hat.mc.runtime.HatHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Let the helmet slot take anything, when luna-hat says so.
 *
 * The Paper plugin does this by cancelling inventory clicks and moving the items
 * itself, once per click shape - cursor, hotbar swap, and so on. Widening the
 * slot's own rule instead means the game keeps doing the moving, so shift-click
 * and every other shape come free and nothing can desync.
 *
 * Only the head slot is widened, and only for a real player. {@code owner} and
 * {@code slot} are private in ArmorSlot but carry the same names on both game
 * lines, which is what lets one mixin serve 1.21 and 26.x alike.
 *
 * The target is named as a string because ArmorSlot is package-private through
 * 1.21 - there is no type to reference - and only becomes public in 26.x.
 */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class ArmorSlotMixin {
	@Shadow
	@Final
	private LivingEntity owner;

	@Shadow
	@Final
	private EquipmentSlot slot;

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void lunaAllowAnyHat(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
		if (slot != EquipmentSlot.HEAD || !(owner instanceof ServerPlayer player)) {
			return;
		}

		if (HatHooks.allowInHelmetSlot(player, stack)) {
			callback.setReturnValue(true);
		}
	}
}
