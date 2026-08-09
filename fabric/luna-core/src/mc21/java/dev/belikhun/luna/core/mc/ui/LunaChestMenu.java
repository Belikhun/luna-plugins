package dev.belikhun.luna.core.mc.ui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

/**
 * A luna chest menu's click handler, as the 1.20-1.21 line spells it.
 *
 * A click on one of these is a button press and nothing else, so the vanilla
 * handling is not called: not delegating is what stops a player dragging an item
 * out of a slot that only exists to be looked at.
 *
 * The 26.x copy lives in luna-core-mc26-fabric under the same name;
 * {@link LunaChestMenuBase} explains why the two exist.
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
