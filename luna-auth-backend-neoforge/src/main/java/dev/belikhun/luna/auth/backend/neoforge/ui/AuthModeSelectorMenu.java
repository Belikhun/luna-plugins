package dev.belikhun.luna.auth.backend.neoforge.ui;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthModeSelectorMenu extends AbstractContainerMenu {
	public static final int CONTAINER_SIZE = 9;

	private final Container container;
	private final Map<Integer, Runnable> actions;
	private final Runnable closeListener;
	private boolean suppressCloseCallback;

	public AuthModeSelectorMenu(int containerId, Inventory playerInventory, Runnable closeListener) {
		super(MenuType.GENERIC_9x1, containerId);
		this.container = new SimpleContainer(CONTAINER_SIZE);
		this.actions = new ConcurrentHashMap<>();
		this.closeListener = closeListener;
		checkContainerSize(this.container, CONTAINER_SIZE);
		this.container.startOpen(playerInventory.player);

		for (int col = 0; col < 9; col++) {
			addSlot(new LockedSlot(this.container, col, 8 + (col * 18), 18));
		}

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInventory, col + (row * 9) + 9, 8 + (col * 18), 50 + (row * 18)));
			}
		}

		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(playerInventory, col, 8 + (col * 18), 108));
		}
	}

	public void clearTopSlots() {
		actions.clear();
		for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
			container.setItem(slot, ItemStack.EMPTY);
		}
	}

	public void setTopSlot(int slot, ItemStack stack, Runnable action) {
		if (slot < 0 || slot >= CONTAINER_SIZE) {
			return;
		}
		container.setItem(slot, stack == null ? ItemStack.EMPTY : stack.copy());
		if (action == null) {
			actions.remove(slot);
		} else {
			actions.put(slot, action);
		}
	}

	public void suppressCloseCallbackOnce() {
		suppressCloseCallback = true;
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		if (slotId >= 0 && slotId < CONTAINER_SIZE) {
			Runnable action = actions.get(slotId);
			if (action != null) {
				action.run();
			}
			return;
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		container.stopOpen(player);
		if (suppressCloseCallback) {
			suppressCloseCallback = false;
			return;
		}
		if (closeListener != null) {
			closeListener.run();
		}
	}

	private static final class LockedSlot extends Slot {
		private LockedSlot(Container container, int slot, int x, int y) {
			super(container, slot, x, y);
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}
	}
}
