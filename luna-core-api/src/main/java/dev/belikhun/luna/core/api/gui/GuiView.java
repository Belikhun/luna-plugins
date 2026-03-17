package dev.belikhun.luna.core.api.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class GuiView implements InventoryHolder {
	private final Inventory inventory;
	private final Map<Integer, GuiClickHandler> handlers;

	public GuiView(int size, Component title) {
		this.inventory = Bukkit.createInventory(this, size, title);
		this.handlers = new HashMap<>();
	}

	public GuiView setItem(int slot, ItemStack item, GuiClickHandler handler) {
		inventory.setItem(slot, item);
		if (handler != null) {
			handlers.put(slot, handler);
		}
		return this;
	}

	public GuiView setItem(int slot, ItemStack item) {
		inventory.setItem(slot, item);
		return this;
	}

	public void open(Player player) {
		player.openInventory(inventory);
	}

	public void handleClick(Player player, InventoryClickEvent event) {
		if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inventory)) {
			return;
		}

		int rawSlot = event.getRawSlot();
		if (rawSlot < 0 || rawSlot >= inventory.getSize()) {
			return;
		}

		if (event.getAction() == InventoryAction.NOTHING
			|| event.getClick() == ClickType.DOUBLE_CLICK
			|| event.getClick().isKeyboardClick()) {
			return;
		}

		GuiClickHandler handler = handlers.get(rawSlot);
		if (handler != null) {
			handler.onClick(player, event, this);
		}
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}
}

