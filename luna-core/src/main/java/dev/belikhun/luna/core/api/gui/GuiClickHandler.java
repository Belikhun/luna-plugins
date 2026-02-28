package dev.belikhun.luna.core.api.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

@FunctionalInterface
public interface GuiClickHandler {
	void onClick(Player player, InventoryClickEvent event, GuiView view);
}
