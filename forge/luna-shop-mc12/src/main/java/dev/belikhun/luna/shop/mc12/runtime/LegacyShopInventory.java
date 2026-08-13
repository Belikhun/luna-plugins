package dev.belikhun.luna.shop.mc12.runtime;

import dev.belikhun.luna.legacy.shop.ShopInventory;
import dev.belikhun.luna.legacy.shop.ShopItems;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * What buying and selling do to a 1.12.2 inventory.
 *
 * **Only the storage half is touched.** Slots 0-35 are the hotbar and the main
 * grid; armour and the offhand sit above that and are not places a shop puts
 * things - counting them would let a player sell the boots they are wearing.
 * Every platform's implementation owes the shared rules that guarantee.
 */
public final class LegacyShopInventory implements ShopInventory<EntityPlayerMP, ItemStack> {
	/** Hotbar plus main grid; armour and offhand live past this on 1.12.2. */
	private static final int STORAGE_SLOTS = 36;

	private final ShopItems<ItemStack> items;

	public LegacyShopInventory(ShopItems<ItemStack> items) {
		this.items = items;
	}

	@Override
	public int countSimilar(EntityPlayerMP player, ItemStack sample) {
		if (player == null || items.isEmpty(sample)) {
			return 0;
		}

		int total = 0;
		int slots = Math.min(STORAGE_SLOTS, player.inventory.getSizeInventory());

		for (int slot = 0; slot < slots; slot += 1) {
			ItemStack content = player.inventory.getStackInSlot(slot);

			if (!items.isEmpty(content) && items.sameItemAndData(content, sample)) {
				total += content.getCount();
			}
		}

		return total;
	}

	@Override
	public int maxAcceptable(EntityPlayerMP player, ItemStack sample) {
		if (player == null || items.isEmpty(sample)) {
			return 0;
		}

		int maxStack = items.maxStackSize(sample);
		int space = 0;
		int slots = Math.min(STORAGE_SLOTS, player.inventory.getSizeInventory());

		for (int slot = 0; slot < slots; slot += 1) {
			ItemStack content = player.inventory.getStackInSlot(slot);

			if (items.isEmpty(content)) {
				space += maxStack;
				continue;
			}

			if (items.sameItemAndData(content, sample)) {
				space += Math.max(0, maxStack - content.getCount());
			}
		}

		return space;
	}

	@Override
	public void removeSimilar(EntityPlayerMP player, ItemStack sample, int amount) {
		if (player == null || items.isEmpty(sample)) {
			return;
		}

		int remaining = amount;
		int slots = Math.min(STORAGE_SLOTS, player.inventory.getSizeInventory());

		for (int slot = 0; slot < slots && remaining > 0; slot += 1) {
			ItemStack content = player.inventory.getStackInSlot(slot);

			if (items.isEmpty(content) || !items.sameItemAndData(content, sample)) {
				continue;
			}

			if (content.getCount() <= remaining) {
				remaining -= content.getCount();
				player.inventory.setInventorySlotContents(slot, ItemStack.EMPTY);
				continue;
			}

			content.setCount(content.getCount() - remaining);
			player.inventory.setInventorySlotContents(slot, content);
			remaining = 0;
		}

		player.inventoryContainer.detectAndSendChanges();
	}

	/**
	 * Hand over the goods, one stack at a time.
	 *
	 * Anything that will not fit is dropped rather than lost: the caller checked
	 * {@link #maxAcceptable} first, so this only happens when the inventory changed
	 * underneath the trade - a hopper, another mod, a second window.
	 */
	@Override
	public void give(EntityPlayerMP player, ItemStack sample, int amount) {
		if (player == null || items.isEmpty(sample)) {
			return;
		}

		int remaining = amount;
		int maxStack = items.maxStackSize(sample);

		while (remaining > 0) {
			int giveAmount = Math.min(maxStack, remaining);
			ItemStack stack = items.withCount(sample, giveAmount);

			if (!player.inventory.addItemStackToInventory(stack)) {
				player.dropItem(stack, false);
			}

			remaining -= giveAmount;
		}

		player.inventoryContainer.detectAndSendChanges();
	}
}
