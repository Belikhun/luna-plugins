package dev.belikhun.luna.core.api.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiManager implements Listener {
	private final Map<Inventory, GuiView> views;

	public GuiManager() {
		this.views = new ConcurrentHashMap<>();
	}

	public void track(GuiView view) {
		views.put(view.getInventory(), view);
	}

	public void untrack(GuiView view) {
		views.remove(view.getInventory());
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		GuiView view = views.get(event.getInventory());
		if (view == null) {
			return;
		}

		event.setCancelled(true);
		if (event.getWhoClicked() instanceof Player player) {
			view.handleClick(player, event);
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		GuiView view = views.get(event.getInventory());
		if (view != null) {
			views.remove(event.getInventory());
		}
	}
}
