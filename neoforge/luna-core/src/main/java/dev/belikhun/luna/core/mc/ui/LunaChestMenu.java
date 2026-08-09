package dev.belikhun.luna.core.mc.ui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

/**
 * A luna chest menu's click handler, as NeoForge's game version spells it.
 *
 * NeoForge 21.1 is Minecraft 1.21, so this is the same override the fabric
 * 1.20-1.21 build carries. It exists separately because the class is what the
 * shared {@code LunaMenuHost} names, and each platform supplies its own.
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
