package dev.belikhun.luna.core.mc12.ui;

import dev.belikhun.luna.legacy.ui.LunaClick;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A chest of buttons, on 1.12.2.
 *
 * The same shape every luna GUI is drawn into: the player cannot take from or put
 * into the top half, each of its slots is an item plus an optional action, and a
 * close callback lets the owner forget the view.
 *
 * **The class hierarchy is inverted from the modern builds, not just renamed.** On
 * this line `Container` *is* the menu (the modern `AbstractContainerMenu`) and
 * `IInventory` is the backing inventory (the modern `Container`). Reading the
 * modern file as a rename table produces something that compiles and is wrong, so
 * this is written against the 1.12.2 API rather than translated.
 *
 * There is also no `MenuType`: a window is opened by sending a GUI id as a string,
 * which is why {@link LunaChestInventory} carries `"minecraft:container"` rather
 * than picking a type constant per row count.
 */
public class LunaChestMenu extends Container {
	private final IInventory container;
	private final Map<Integer, Consumer<LunaClick>> actions;
	private final Runnable closeListener;
	private final int containerSize;

	private boolean suppressCloseCallback;

	public LunaChestMenu(InventoryPlayer playerInventory, int rows, Runnable closeListener) {
		int safeRows = Math.max(1, Math.min(6, rows));

		this.containerSize = safeRows * 9;
		this.container = new InventoryBasic("luna", false, containerSize);
		this.actions = new ConcurrentHashMap<Integer, Consumer<LunaClick>>();
		this.closeListener = closeListener;

		for (int row = 0; row < safeRows; row += 1) {
			for (int col = 0; col < 9; col += 1) {
				addSlotToContainer(new LockedSlot(container, row * 9 + col, 8 + (col * 18), 18 + (row * 18)));
			}
		}

		// the player's own inventory sits below whatever the top half is, so both
		// offsets follow the row count rather than a constant
		int inventoryTop = 32 + (safeRows * 18);

		for (int row = 0; row < 3; row += 1) {
			for (int col = 0; col < 9; col += 1) {
				addSlotToContainer(new Slot(playerInventory, col + (row * 9) + 9, 8 + (col * 18), inventoryTop + (row * 18)));
			}
		}

		for (int col = 0; col < 9; col += 1) {
			addSlotToContainer(new Slot(playerInventory, col, 8 + (col * 18), inventoryTop + 58));
		}
	}

	/** How many slots the top half has. */
	public final int containerSize() {
		return containerSize;
	}

	/** The inventory the window is opened against. */
	final IInventory inventory() {
		return container;
	}

	public final void clearTopSlots() {
		actions.clear();

		for (int slot = 0; slot < containerSize; slot += 1) {
			container.setInventorySlotContents(slot, ItemStack.EMPTY);
		}
	}

	/** An item that is only there to be looked at: a filler pane, a spacer, a label. */
	public final void setDecoration(int slot, ItemStack stack) {
		setTopSlot(slot, stack, (Consumer<LunaClick>) null);
	}

	/** A button whose action does not care which mouse button pressed it. */
	public final void setTopSlot(int slot, ItemStack stack, final Runnable action) {
		setTopSlot(slot, stack, action == null ? null : new Consumer<LunaClick>() {
			@Override
			public void accept(LunaClick click) {
				action.run();
			}
		});
	}

	/** A button whose action reads the click: left buys, right sells, and so on. */
	public final void setTopSlot(int slot, ItemStack stack, Consumer<LunaClick> action) {
		if (slot < 0 || slot >= containerSize) {
			return;
		}

		container.setInventorySlotContents(slot, stack == null ? ItemStack.EMPTY : stack.copy());

		if (action == null) {
			actions.remove(Integer.valueOf(slot));
		} else {
			actions.put(Integer.valueOf(slot), action);
		}
	}

	/**
	 * Keep the next close from reaching the owner.
	 *
	 * Opening a second menu closes the first one, and that close is the same event
	 * a player pressing Escape produces; without this the owner would forget the
	 * view it is in the middle of replacing.
	 */
	public final void suppressCloseCallbackOnce() {
		suppressCloseCallback = true;
	}

	/**
	 * Every click in the window, before vanilla moves anything.
	 *
	 * Returning `ItemStack.EMPTY` for a top-half slot is what makes the button a
	 * button: vanilla's own handling would otherwise pick the item up, and a locked
	 * slot only stops the *transfer*, not the cursor stack it hands back.
	 */
	@Override
	public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player) {
		if (slotId >= 0 && slotId < containerSize) {
			Consumer<LunaClick> action = actions.get(Integer.valueOf(slotId));

			if (action != null) {
				action.accept(LunaClick.of(slotId, dragType, clickType.name()));
			}

			return ItemStack.EMPTY;
		}

		return super.slotClick(slotId, dragType, clickType, player);
	}

	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return true;
	}

	@Override
	public void onContainerClosed(EntityPlayer player) {
		super.onContainerClosed(player);

		if (suppressCloseCallback) {
			suppressCloseCallback = false;
			return;
		}

		if (closeListener != null) {
			closeListener.run();
		}
	}

	/** A slot the player may look at and click, but never move anything in or out of. */
	private static final class LockedSlot extends Slot {
		private LockedSlot(IInventory inventory, int slot, int x, int y) {
			super(inventory, slot, x, y);
		}

		@Override
		public boolean canTakeStack(EntityPlayer player) {
			return false;
		}

		@Override
		public boolean isItemValid(ItemStack stack) {
			return false;
		}
	}
}
