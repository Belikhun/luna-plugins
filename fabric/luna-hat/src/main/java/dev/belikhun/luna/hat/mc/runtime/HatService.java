package dev.belikhun.luna.hat.mc.runtime;

import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Wearing something that is not a helmet.
 *
 * Two ways in, and both land here: the {@code /hat} command, and dropping an item
 * straight into the helmet slot - which the game normally refuses and
 * {@code ArmorSlotMixin} allows.
 *
 * The permissions are the ones the Paper plugin declares, with the defaults it
 * declares: {@code hat.blocks} and {@code hat.items} are on for everyone until an
 * operator takes them away. That is why they are read through
 * {@code hasPermissionOrDefault} rather than the plain check - an unset node here
 * means yes, not no.
 */
public final class HatService {
	private static final String PERMISSION_BLOCKS = "hat.blocks";
	private static final String PERMISSION_ITEMS = "hat.items";
	private static final String MESSAGE_EQUIPPED = "<green>✔ Đã đội vật phẩm lên mũ.</green>";
	private static final String MESSAGE_NO_PERMISSION = "<red>❌ Bạn không có quyền đội vật phẩm này.</red>";

	private final PermissionService permissions;

	public HatService(PermissionService permissions) {
		this.permissions = permissions;
	}

	/**
	 * Swap what the player is holding with what they are wearing.
	 *
	 * An empty hand takes the hat off rather than doing nothing, which is how a
	 * player gets a non-helmet back out of the slot.
	 */
	public void swapWithMainHand(ServerPlayer player) {
		ItemStack held = player.getMainHandItem();

		if (!mayWear(player, held)) {
			player.sendSystemMessage(LunaTextComponents.mini(MESSAGE_NO_PERMISSION));
			return;
		}

		ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);

		if (held.isEmpty()) {
			player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, helmet.copy());
			player.containerMenu.broadcastChanges();
			player.sendSystemMessage(LunaTextComponents.mini(MESSAGE_EQUIPPED));
			return;
		}

		// only one goes on the head; the rest stays where it was, so a stack of
		// blocks is not consumed by wearing one of them
		ItemStack hat = held.copyWithCount(1);
		ItemStack remaining = held.copy();
		remaining.shrink(1);

		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, remaining);
		player.setItemSlot(EquipmentSlot.HEAD, hat);
		giveOrDrop(player, helmet);
		player.containerMenu.broadcastChanges();
		player.sendSystemMessage(LunaTextComponents.mini(MESSAGE_EQUIPPED));
	}

	/** Whether this player may wear this item. An empty stack is always allowed. */
	public boolean mayWear(ServerPlayer player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return true;
		}

		if (permissions == null || !permissions.isAvailable()) {
			return true;
		}

		String permission = stack.getItem() instanceof net.minecraft.world.item.BlockItem
			? PERMISSION_BLOCKS
			: PERMISSION_ITEMS;

		return permissions.hasPermissionOrDefault(player.getUUID(), permission, true);
	}

	private void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}

		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}
}
