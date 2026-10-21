package dev.belikhun.luna.patpat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A chest menu: items with click actions; nothing in it can be taken or moved. */
final class Menu implements InventoryHolder {
	private final Inventory inventory;
	private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

	Menu(int rows, String title) {
		this.inventory = Bukkit.createInventory(this, rows * 9, Component.text(title, NamedTextColor.DARK_PURPLE));
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
		inventory.setItem(slot, item);
		if (action != null) actions.put(slot, action); else actions.remove(slot);
		return this;
	}

	void fillBorder(int row) {
		ItemStack pane = item(Material.PINK_STAINED_GLASS_PANE, " ", List.of(), false);
		for (int i = row * 9; i < row * 9 + 9; i++) {
			if (inventory.getItem(i) == null) inventory.setItem(i, pane);
		}
	}

	void open(Player p) {
		p.openInventory(inventory);
	}

	static ItemStack item(Material material, String name, List<String> lore) {
		return item(material, name, lore, false);
	}

	static ItemStack item(Material material, String name, List<String> lore, boolean glow) {
		ItemStack it = new ItemStack(material);
		ItemMeta m = it.getItemMeta();
		m.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
		List<Component> lines = new ArrayList<>();
		for (String l : lore) lines.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		m.lore(lines);
		if (glow) m.setEnchantmentGlintOverride(true);
		it.setItemMeta(m);
		return it;
	}

	/** Routes clicks in any open Menu to its actions. */
	static final class Clicks implements Listener {
		@EventHandler
		public void onClick(InventoryClickEvent e) {
			if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
			e.setCancelled(true);
			if (e.getClickedInventory() != e.getView().getTopInventory()) return;
			Consumer<InventoryClickEvent> a = menu.actions.get(e.getRawSlot());
			if (a != null) a.accept(e);
		}

		@EventHandler
		public void onDrag(InventoryDragEvent e) {
			if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
		}
	}
}
