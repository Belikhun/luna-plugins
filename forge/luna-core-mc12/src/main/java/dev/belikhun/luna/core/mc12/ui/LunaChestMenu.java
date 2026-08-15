package dev.belikhun.luna.core.mc12.ui;

import dev.belikhun.luna.legacy.ui.LunaClick;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketSetSlot;

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

	/**
	 * Whether a click handler is on the stack, for anything that must wait for it.
	 *
	 * Static because it is a property of the server thread, not of one menu: a
	 * handler on one screen routinely opens another, and the screen being opened is
	 * the one that needs to know. Clicks are handled on the server thread only, so
	 * nothing else can observe it mid-flight.
	 */
	private static boolean dispatchingClick;

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

	/** Whether a click on one of these menus is being handled right now. */
	static boolean isDispatchingClick() {
		return dispatchingClick;
	}

	/**
	 * Every click in the window, before vanilla moves anything.
	 *
	 * Nothing is moved: a top-half slot is a button, and the locked slot only stops
	 * the *transfer*, not the cursor stack vanilla's own handling would hand back.
	 *
	 * **What is returned decides which branch of `processClickWindow` runs**, and the
	 * two branches are not equally cheap. The client has already run vanilla's click
	 * on its own copy of the window, so it believes it just picked the button up;
	 * returning what it computed puts the server on the agreeing branch, which costs
	 * two small packets. Disagreeing costs a resend of all ninety slots *and* locks
	 * the container until the client's confirm-transaction arrives, so a second click
	 * inside one round trip is dropped. For an ordinary left or right click the client
	 * returns a copy of what the slot held, which is what is captured below; the other
	 * click types compute something we would have to guess at, so they take the
	 * disagreeing branch deliberately - it is slower, not wrong, and it self-heals.
	 *
	 * The agreeing branch sends nothing back, though: it runs under
	 * `isChangingQuantityOnly`, which gags both `sendSlotContents` and
	 * `updateHeldItem`. So the client's whole idea of the click - the slot it emptied
	 * and the stack it put on the cursor - is corrected here by hand, after the action
	 * has had its chance to redraw the slot.
	 */
	@Override
	public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player) {
		if (slotId < 0 || slotId >= containerSize) {
			return super.slotClick(slotId, dragType, clickType, player);
		}

		ItemStack shown = container.getStackInSlot(slotId).copy();
		Consumer<LunaClick> action = actions.get(Integer.valueOf(slotId));

		if (action != null) {
			boolean nested = dispatchingClick;

			dispatchingClick = true;

			try {
				action.accept(LunaClick.of(slotId, dragType, clickType.name()));
			} finally {
				dispatchingClick = nested;
			}
		}

		resyncClick(player, slotId);

		return clickType == ClickType.PICKUP ? shown : ItemStack.EMPTY;
	}

	/**
	 * Undo the click the client performed on its own copy of the window.
	 *
	 * Two packets: what the slot really holds, and what the cursor really holds.
	 * Without the second one the button the client believes it picked up stays stuck
	 * to the mouse, since the branch that would have cleared it is gagged.
	 */
	private void resyncClick(EntityPlayer player, int slotId) {
		if (!(player instanceof EntityPlayerMP)) {
			return;
		}

		EntityPlayerMP serverPlayer = (EntityPlayerMP) player;

		serverPlayer.connection.sendPacket(new SPacketSetSlot(windowId, slotId, container.getStackInSlot(slotId)));
		serverPlayer.connection.sendPacket(new SPacketSetSlot(-1, -1, serverPlayer.inventory.getItemStack()));
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
