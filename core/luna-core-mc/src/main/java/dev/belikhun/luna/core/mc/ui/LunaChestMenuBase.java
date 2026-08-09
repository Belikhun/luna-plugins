package dev.belikhun.luna.core.mc.ui;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A chest of buttons: the shape every luna GUI on Fabric is drawn into.
 *
 * The player cannot take from or put into the top half; each of its slots is an
 * item plus an optional action, and the close callback lets the owner forget the
 * view. Row count is a parameter because the screens differ - the server selector
 * wants six rows, the auth mode picker one - and nothing else about them does.
 *
 * The one member that is not here is {@code clicked(...)}: 26.x replaced its
 * {@code ClickType} argument with {@code ContainerInput}, so there is no override
 * that compiles against both game lines. {@link LunaChestMenu} exists once per
 * line to supply it and calls {@link #handleClick(int, int, String)}, which is
 * the whole of the behaviour - so a screen anywhere in the fleet gets the split
 * for free rather than repeating it per module.
 *
 * {@code startOpen}/{@code stopOpen} are deliberately not called. They took a
 * {@code Player} through 1.21 and a {@code ContainerUser} from 26.x, and
 * {@link SimpleContainer} does nothing in either - a virtual container has no
 * block to notify - so skipping them costs nothing and removes a second split.
 */
public abstract class LunaChestMenuBase extends AbstractContainerMenu {
	private final Container container;
	private final Map<Integer, Consumer<LunaClick>> actions;
	private final Runnable closeListener;
	private final int containerSize;
	private boolean suppressCloseCallback;

	protected LunaChestMenuBase(int containerId, Inventory playerInventory, int rows, Runnable closeListener) {
		super(menuTypeFor(rows), containerId);

		int safeRows = Math.max(1, Math.min(6, rows));

		this.containerSize = safeRows * 9;
		this.container = new SimpleContainer(containerSize);
		this.actions = new ConcurrentHashMap<>();
		this.closeListener = closeListener;
		checkContainerSize(this.container, containerSize);

		for (int row = 0; row < safeRows; row++) {
			for (int col = 0; col < 9; col++) {
				int slot = row * 9 + col;
				addSlot(new LockedSlot(this.container, slot, 8 + (col * 18), 18 + (row * 18)));
			}
		}

		// the player's own inventory sits below whatever the top half is, so both
		// offsets follow the row count rather than a constant
		int inventoryTop = 32 + (safeRows * 18);

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInventory, col + (row * 9) + 9, 8 + (col * 18), inventoryTop + (row * 18)));
			}
		}

		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(playerInventory, col, 8 + (col * 18), inventoryTop + 58));
		}
	}

	/** How many slots the top half has. */
	public final int containerSize() {
		return containerSize;
	}

	public final void clearTopSlots() {
		actions.clear();

		for (int slot = 0; slot < containerSize; slot++) {
			container.setItem(slot, ItemStack.EMPTY);
		}
	}

	/** An item that is only there to be looked at: a filler pane, a spacer, a label. */
	public final void setDecoration(int slot, ItemStack stack) {
		setTopSlot(slot, stack, (Consumer<LunaClick>) null);
	}

	/** A button whose action does not care which mouse button pressed it. */
	public final void setTopSlot(int slot, ItemStack stack, Runnable action) {
		setTopSlot(slot, stack, action == null ? null : click -> action.run());
	}

	/** A button whose action reads the click: left buys, right sells, and so on. */
	public final void setTopSlot(int slot, ItemStack stack, Consumer<LunaClick> action) {
		if (slot < 0 || slot >= containerSize) {
			return;
		}

		container.setItem(slot, stack == null ? ItemStack.EMPTY : stack.copy());

		if (action == null) {
			actions.remove(slot);
		} else {
			actions.put(slot, action);
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
	 * Run the slot's action, if it has one. Clicks below the menu do nothing.
	 *
	 * @param kindName the game's own name for the click kind; see {@link LunaClick}
	 *                 for why it arrives as a string rather than as the enum
	 */
	protected final void handleClick(int slotId, int button, String kindName) {
		if (slotId < 0 || slotId >= containerSize) {
			return;
		}

		Consumer<LunaClick> action = actions.get(slotId);

		if (action != null) {
			action.accept(LunaClick.of(slotId, button, kindName));
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

		if (suppressCloseCallback) {
			suppressCloseCallback = false;
			return;
		}

		if (closeListener != null) {
			closeListener.run();
		}
	}

	private static MenuType<?> menuTypeFor(int rows) {
		return switch (Math.max(1, Math.min(6, rows))) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
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
