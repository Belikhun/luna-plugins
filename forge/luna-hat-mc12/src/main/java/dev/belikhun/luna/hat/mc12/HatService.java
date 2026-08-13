package dev.belikhun.luna.hat.mc12;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.permission.PermissionService;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/**
 * Wearing something that is not a helmet, on 1.12.2.
 *
 * The same behaviour as the modern builds' `HatService`, against MCP names. One
 * difference is worth stating rather than discovering: on the modern lines a
 * mixin also lets a player *drag* any item into the helmet slot, and there is no
 * mixin here - so `/hat` is the only way in on this line. The command is the
 * part players actually use, and adding a mixin loader to the 1.12.2 build for
 * the drag path is not worth what it costs.
 *
 * The permissions and their defaults are the Paper plugin's: `hat.blocks` and
 * `hat.items` are on for everyone until an operator takes them away, which is
 * why they go through `hasPermissionOrDefault`. On this line that matters twice
 * over - permissions are mirrored from the proxy, so a player whose snapshot has
 * not landed yet reads as *unset*, and an unset node here has to mean yes.
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
	public void swapWithMainHand(EntityPlayerMP player) {
		ItemStack held = player.getHeldItemMainhand();

		if (!mayWear(player, held)) {
			LunaTextComponents.send(player, MESSAGE_NO_PERMISSION);
			return;
		}

		ItemStack helmet = player.getItemStackFromSlot(EntityEquipmentSlot.HEAD);

		if (held.isEmpty()) {
			player.setItemStackToSlot(EntityEquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setHeldItem(EnumHand.MAIN_HAND, helmet.copy());
			player.openContainer.detectAndSendChanges();
			LunaTextComponents.send(player, MESSAGE_EQUIPPED);
			return;
		}

		// only one goes on the head; the rest stays where it was, so a stack of
		// blocks is not consumed by wearing one of them
		ItemStack hat = held.copy();
		hat.setCount(1);

		ItemStack remaining = held.copy();
		remaining.shrink(1);

		player.setHeldItem(EnumHand.MAIN_HAND, remaining);
		player.setItemStackToSlot(EntityEquipmentSlot.HEAD, hat);
		giveOrDrop(player, helmet);
		player.openContainer.detectAndSendChanges();

		LunaTextComponents.send(player, MESSAGE_EQUIPPED);
	}

	/** Whether this player may wear this item. An empty stack is always allowed. */
	public boolean mayWear(EntityPlayerMP player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return true;
		}

		// no permission service at all means no opinion, and the defaults here are
		// permissive - refusing would take hat away from everyone the moment the
		// proxy went quiet
		if (permissions == null || !permissions.isAvailable()) {
			return true;
		}

		String permission = stack.getItem() instanceof ItemBlock ? PERMISSION_BLOCKS : PERMISSION_ITEMS;

		return permissions.hasPermissionOrDefault(player.getUniqueID(), permission, true);
	}

	private void giveOrDrop(EntityPlayerMP player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}

		if (!player.inventory.addItemStackToInventory(stack)) {
			player.dropItem(stack, false);
		}
	}
}
