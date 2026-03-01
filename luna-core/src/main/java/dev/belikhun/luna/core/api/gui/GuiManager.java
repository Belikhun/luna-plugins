package dev.belikhun.luna.core.api.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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
		Inventory topInventory = event.getView().getTopInventory();
		GuiView view = views.get(topInventory);
		if (view == null) {
			return;
		}

		event.setCancelled(true);
		if (event.getWhoClicked() instanceof Player player) {
			view.handleClick(player, event);
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		Inventory topInventory = event.getView().getTopInventory();
		if (!views.containsKey(topInventory)) {
			return;
		}

		int topSize = topInventory.getSize();
		for (int rawSlot : event.getRawSlots()) {
			if (rawSlot < topSize) {
				event.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		Inventory topInventory = event.getView().getTopInventory();
		GuiView view = views.get(topInventory);
		if (view != null) {
			views.remove(topInventory);
		}
	}
}
