package dev.belikhun.luna.core.mc.ui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

/**
 * A luna chest menu's click handler, for every line calling the enum
 * {@code ClickType}: 1.19 through 1.21.x, on fabric, neoforge and forge alike.
 *
 * A click on one of these is a button press and nothing else, so the vanilla
 * handling is not called: not delegating is what stops a player dragging an item
 * out of a slot that only exists to be looked at.
 *
 * 26.x renamed the enum and takes menu-containerinput instead;
 * {@link LunaChestMenuBase} explains why only this override differs.
 */
public final class LunaChestMenu extends LunaChestMenuBase {
	public LunaChestMenu(int containerId, Inventory playerInventory, int rows, Runnable closeListener) {
		super(containerId, playerInventory, rows, closeListener);
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		handleClick(slotId, button, clickType.name());
	}
}
