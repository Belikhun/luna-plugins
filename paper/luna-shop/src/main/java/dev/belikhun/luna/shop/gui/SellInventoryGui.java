package dev.belikhun.luna.shop.gui;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.ui.LunaLore;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.shop.api.ShopResult;
import dev.belikhun.luna.shop.service.ShopService;
import dev.belikhun.luna.shop.service.ShopService.SellLot;
import dev.belikhun.luna.shop.service.ShopService.SellPlan;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The bulk sell tray: an empty inventory the player drops items into, with the running
 * value of everything in it in the footer.
 *
 * It is not a {@link dev.belikhun.luna.core.api.gui.GuiView} because that cancels every
 * click in the menu, which is exactly what a tray must not do; the item area is the
 * player's to fill and the footer is the only part held shut.
 */
public final class SellInventoryGui implements Listener {
	/** Slots the player may fill; the rest is the standard nine-slot footer. */
	public static final int ITEM_SLOTS = 45;
	private static final int SIZE = 54;
	private static final int BACK_SLOT = 45;
	private static final int TOTAL_SLOT = 48;
	private static final int SELL_SLOT = 49;
	private static final int RETURN_SLOT = 50;
	private static final int CLOSE_SLOT = 52;
	private static final int MAX_LOT_LINES = 7;

	private final JavaPlugin plugin;
	private final ShopService service;
	private final LunaLogger logger;
	private final Consumer<Player> backToShop;
	/** Sales waiting on the wallet, so a second click cannot sell the same tray twice. */
	private final java.util.Set<UUID> settling = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public SellInventoryGui(JavaPlugin plugin, ShopService service, LunaLogger logger, Consumer<Player> backToShop) {
		this.plugin = plugin;
		this.service = service;
		this.logger = logger;
		this.backToShop = backToShop;

		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	public void open(Player player) {
		Tray tray = new Tray(player.getUniqueId());
		refresh(player, tray);
		player.openInventory(tray.getInventory());
	}

	/**
	 * Empty the tray back into the player's hands.
	 *
	 * Called on close, on quit and on shutdown: a tray holds real items and nothing in
	 * it is ever the shop's until the sale goes through.
	 */
	public void returnEverything(Player player, Inventory inventory) {
		for (int slot = 0; slot < ITEM_SLOTS; slot++) {
			ItemStack content = inventory.getItem(slot);
			if (content == null || content.getType().isAir()) {
				continue;
			}

			inventory.clear(slot);

			for (ItemStack leftover : player.getInventory().addItem(content).values()) {
				player.getWorld().dropItemNaturally(player.getLocation(), leftover);
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof Tray tray)) {
			return;
		}

		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		if (!tray.owner().equals(player.getUniqueId())) {
			event.setCancelled(true);
			return;
		}

		int rawSlot = event.getRawSlot();
		boolean inTray = rawSlot >= 0 && rawSlot < SIZE;

		// a double click gathers matching stacks from everywhere the footer included, so
		// it is refused outright when the cursor could sweep the footer up
		if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR && isFooterPane(event.getCursor())) {
			event.setCancelled(true);
			return;
		}

		if (inTray && rawSlot >= ITEM_SLOTS) {
			event.setCancelled(true);
			handleFooterClick(player, tray, rawSlot);
			return;
		}

		// everything else is the player filling or emptying the item area, which is what
		// the tray is for; a shift-click from the bag cannot spill into the footer
		// because those nine slots are never empty
		redrawLater(player, tray);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onDrag(InventoryDragEvent event) {
		if (!(event.getInventory().getHolder() instanceof Tray tray)) {
			return;
		}

		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		for (int rawSlot : event.getRawSlots()) {
			if (rawSlot >= ITEM_SLOTS && rawSlot < SIZE) {
				event.setCancelled(true);
				return;
			}
		}

		redrawLater(player, tray);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getInventory().getHolder() instanceof Tray)) {
			return;
		}

		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}

		Inventory inventory = event.getInventory();

		// a sale in flight has already taken its items out; whatever is left is the
		// player's, and it goes back to them either way
		plugin.getServer().getScheduler().runTask(plugin, () -> returnEverything(player, inventory));
	}

	private void handleFooterClick(Player player, Tray tray, int slot) {
		switch (slot) {
			case BACK_SLOT -> {
				returnEverything(player, tray.getInventory());
				backToShop.accept(player);
			}
			case SELL_SLOT -> sell(player, tray);
			case RETURN_SLOT -> {
				returnEverything(player, tray.getInventory());
				refresh(player, tray);
			}
			case CLOSE_SLOT -> player.closeInventory();
			default -> {
			}
		}
	}

	private void sell(Player player, Tray tray) {
		UUID playerId = player.getUniqueId();

		if (!settling.add(playerId)) {
			logger.warn("Bỏ qua click bán hàng loạt trùng của " + player.getName() + " (" + playerId + ").");
			return;
		}

		CompletableFuture<ShopResult> pending;

		try {
			pending = service.sellInventoryAsync(player, tray.getInventory(), ITEM_SLOTS);
		} catch (RuntimeException failure) {
			settling.remove(playerId);
			throw failure;
		}

		pending.whenComplete((result, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
			settling.remove(playerId);

			Player current = plugin.getServer().getPlayer(playerId);
			if (current == null || !current.isOnline()) {
				return;
			}

			current.sendMessage(LunaUi.mini(failure != null || result == null
				? "<red>❌ Giao dịch không hoàn tất được. Vui lòng thử lại.</red>"
				: result.message()));

			if (current.getOpenInventory().getTopInventory().getHolder() instanceof Tray open) {
				refresh(current, open);
			}
		}));
	}

	private boolean isFooterPane(ItemStack stack) {
		return stack != null && stack.getType() == Material.GRAY_STAINED_GLASS_PANE;
	}

	/** Close every open tray and hand the items back; for shutdown and reloads. */
	public void closeAll() {
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			if (player.getOpenInventory().getTopInventory().getHolder() instanceof Tray) {
				returnEverything(player, player.getOpenInventory().getTopInventory());
				player.closeInventory();
			}
		}
	}

	/** Redraw after the click has been applied, since the item area changes with it. */
	private void redrawLater(Player player, Tray tray) {
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.getOpenInventory().getTopInventory().equals(tray.getInventory())) {
				refresh(player, tray);
			}
		});
	}

	private void refresh(Player player, Tray tray) {
		Inventory inventory = tray.getInventory();
		SellPlan plan = service.planInventorySale(player, inventory, ITEM_SLOTS);

		ItemStack spacer = item(Material.GRAY_STAINED_GLASS_PANE, "<gray> ", List.of());
		for (int slot = ITEM_SLOTS; slot < SIZE; slot++) {
			inventory.setItem(slot, spacer);
		}

		inventory.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>← Quay lại cửa hàng", List.of(
			plainLine(LunaPalette.NEUTRAL_500, "Vật phẩm trong khay sẽ được trả lại.")
		)));
		inventory.setItem(TOTAL_SLOT, totalItem(player, plan));
		inventory.setItem(SELL_SLOT, sellItem(plan));
		inventory.setItem(RETURN_SLOT, item(Material.HOPPER, "<yellow>⟲ Lấy lại toàn bộ", List.of(
			plainLine(LunaPalette.NEUTRAL_500, "Trả mọi vật phẩm trong khay về túi đồ."),
			actionLine("Chuột trái", "lấy lại")
		)));
		inventory.setItem(CLOSE_SLOT, item(Material.OAK_DOOR, "<red>Đóng", List.of(
			plainLine(LunaPalette.NEUTRAL_500, "Vật phẩm trong khay sẽ được trả lại.")
		)));
	}

	/** The running total: what the tray holds, what it is worth, and what it cannot sell. */
	private ItemStack totalItem(Player player, SellPlan plan) {
		ArrayList<String> lore = new ArrayList<>();
		lore.add(coloredPair(LunaPalette.INFO_500, "♦ Số vật phẩm:", LunaPalette.NEUTRAL_100, String.valueOf(plan.sellableCount())));
		lore.add(coloredPair(LunaPalette.INFO_500, "♦ Số loại:", LunaPalette.NEUTRAL_100, String.valueOf(countSellableLots(plan))));
		lore.add("");

		int shown = 0;

		for (SellLot lot : plan.lots()) {
			if (lot.sellable() <= 0) {
				continue;
			}

			if (shown >= MAX_LOT_LINES) {
				lore.add(plainLine(LunaPalette.NEUTRAL_500, "… và " + (countSellableLots(plan) - shown) + " loại khác"));
				break;
			}

			lore.add(plainLine(LunaPalette.NEUTRAL_100, "• " + itemName(lot) + " <gray>×" + lot.sellable()
				+ "</gray> <gold>" + service.formatMoney(lot.total()) + "</gold>"));
			shown++;
		}

		if (plan.rejectedCount() > 0) {
			lore.add("");
			lore.add(plainLine(LunaPalette.DANGER_500, "⚠ " + plan.rejectedCount() + " vật phẩm không bán được"));
			lore.add(plainLine(LunaPalette.NEUTRAL_500, "Chúng sẽ được trả lại khi bạn đóng khay."));
		}

		if (plan.limitedCount() > 0) {
			lore.add("");
			lore.add(plainLine(LunaPalette.WARNING_500, "⚠ " + plan.limitedCount() + " loại đã chạm hạn mức ngày"));
			lore.add(plainLine(LunaPalette.NEUTRAL_500, "Reset " + service.tradeLimitResetTimeText() + "."));
		}

		lore.add("");
		lore.add(coloredPair(LunaPalette.SUCCESS_500, "💵 Tổng nhận được:", LunaPalette.NEUTRAL_100, service.formatMoney(plan.total())));

		return item(Material.GOLD_INGOT, "<gold>💰 Tổng giá trị khay bán", lore);
	}

	private ItemStack sellItem(SellPlan plan) {
		if (plan.sellableCount() <= 0) {
			return item(Material.GRAY_DYE, "<gray>✔ Bán tất cả", List.of(
				plainLine(LunaPalette.NEUTRAL_500, "Đặt vật phẩm vào khay để bán.")
			));
		}

		return item(Material.EMERALD_BLOCK, "<green>✔ Bán tất cả", List.of(
			coloredPair(LunaPalette.SUCCESS_500, "💵 Nhận về:", LunaPalette.NEUTRAL_100, service.formatMoney(plan.total())),
			coloredPair(LunaPalette.INFO_500, "♦ Số vật phẩm:", LunaPalette.NEUTRAL_100, String.valueOf(plan.sellableCount())),
			"",
			actionLine("Chuột trái", "bán toàn bộ khay")
		));
	}

	private int countSellableLots(SellPlan plan) {
		int lots = 0;

		for (SellLot lot : plan.lots()) {
			if (lot.sellable() > 0) {
				lots++;
			}
		}

		return lots;
	}

	private String itemName(SellLot lot) {
		ItemStack stack = lot.item().itemStack();
		ItemMeta meta = stack.getItemMeta();

		if (meta != null && meta.hasDisplayName()) {
			return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
		}

		if (meta != null && meta.hasItemName()) {
			return PlainTextComponentSerializer.plainText().serialize(meta.itemName());
		}

		return lot.item().id();
	}

	private ItemStack item(Material material, String title, List<String> loreLines) {
		ArrayList<Component> lore = new ArrayList<>();

		for (String line : loreLines) {
			for (String wrapped : LunaLore.wrapLoreLine(line)) {
				lore.add(wrapped.isEmpty() ? Component.empty() : LunaUi.mini(wrapped));
			}
		}

		return LunaUi.item(material, title, lore);
	}

	private String plainLine(String color, String text) {
		return "<color:" + color + ">" + text + "</color>";
	}

	private String coloredPair(String labelColor, String label, String valueColor, String value) {
		return "<color:" + labelColor + ">" + label + "</color> <color:" + valueColor + ">" + value + "</color>";
	}

	private String actionLine(String button, String action) {
		return plainLine(LunaPalette.NEUTRAL_500,
			"▶ <color:" + LunaPalette.INFO_300 + "><bold>" + button + "</bold></color> để " + action);
	}

	/** The tray's own holder, which is how every listener here recognises one. */
	private static final class Tray implements InventoryHolder {
		private final Inventory inventory;
		private final UUID owner;

		private Tray(UUID owner) {
			this.owner = owner;
			this.inventory = org.bukkit.Bukkit.createInventory(this, SIZE, LunaUi.guiTitleBreadcrumb("Luna Shop", "Khay Bán"));
		}

		public UUID owner() {
			return owner;
		}

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}
}
