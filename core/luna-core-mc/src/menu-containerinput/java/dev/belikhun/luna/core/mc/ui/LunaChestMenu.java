package dev.belikhun.luna.core.mc.ui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

/**
 * A luna chest menu's click handler, as the 26.x line spells it.
 *
 * 26.x renamed {@code ClickType} to {@code ContainerInput}, which is the whole
 * reason this class exists twice; every line before it takes menu-clicktype. The
 * two enums carry the same constants, so both copies hand the base the constant's
 * name and {@code LunaClick} maps it once - which is what keeps the base itself
 * in the trunk.
 */
public final class LunaChestMenu extends LunaChestMenuBase {
	public LunaChestMenu(int containerId, Inventory playerInventory, int rows, Runnable closeListener) {
		super(containerId, playerInventory, rows, closeListener);
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		handleClick(slotId, button, input.name());
	}
}
