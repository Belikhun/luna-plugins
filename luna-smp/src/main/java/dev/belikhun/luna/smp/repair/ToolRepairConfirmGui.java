package dev.belikhun.luna.smp.repair;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.MessageFormatter;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.api.ui.LunaUi;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ToolRepairConfirmGui implements Listener {
	private static final int SIZE = 27;
	private static final int ITEM_SLOT = 13;
	private static final int CONFIRM_SLOT = 11;
	private static final int CANCEL_SLOT = 15;
	private static final int RECEIPT_TARGET_WIDTH_PX = 140;

	private final ToolRepairService service;
	private final MessageFormatter formatter;
	private final LunaLogger logger;

	public ToolRepairConfirmGui(ToolRepairService service, MessageFormatter formatter, LunaLogger logger) {
		this.service = service;
		this.formatter = formatter;
		this.logger = logger.scope("RepairGui");
	}

	public void open(Player player) {
		ItemStack itemInHand = player.getInventory().getItemInMainHand();
		if (itemInHand.getType() == Material.AIR) {
			send(player, "messages.must-hold-item");
			return;
		}

		if (!service.isRepairable(itemInHand)) {
			send(player, "messages.not-repairable");
			return;
		}

		if (!service.isDamaged(itemInHand)) {
			send(player, "messages.not-damaged");
			return;
		}

		ToolRepairService.RepairQuote quote = service.quote(itemInHand);
		Inventory inventory = Bukkit.createInventory(new RepairHolder(player.getUniqueId()), SIZE, LunaUi.guiTitle("Xác Nhận Sửa Dụng Cụ"));

		fillBackground(inventory);
		inventory.setItem(ITEM_SLOT, buildReceiptItem(itemInHand, quote));
		inventory.setItem(CONFIRM_SLOT, LunaUi.item(
			Material.LIME_CONCRETE,
			"<color:" + LunaPalette.SUCCESS_300 + "><b>✔ Xác Nhận Sửa</b></color>",
			List.of(
				LunaUi.mini("<color:" + LunaPalette.NEUTRAL_300 + ">Trừ tiền và sửa ngay.</color>")
			)
		));
		inventory.setItem(CANCEL_SLOT, LunaUi.item(
			Material.RED_CONCRETE,
			"<color:" + LunaPalette.DANGER_300 + "><b>❌ Hủy Bỏ</b></color>",
			List.of(
				LunaUi.mini("<color:" + LunaPalette.NEUTRAL_300 + ">Đóng cửa sổ xác nhận.</color>")
			)
		));

		player.openInventory(inventory);
	}

	@EventHandler(ignoreCancelled = true)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof RepairHolder holder)) {
			return;
		}

		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		if (!holder.playerId().equals(player.getUniqueId())) {
			return;
		}

		int slot = event.getRawSlot();
		if (slot == CANCEL_SLOT) {
			player.closeInventory();
			send(player, "messages.repair-cancelled");
			return;
		}

		if (slot != CONFIRM_SLOT) {
			return;
		}

		handleConfirm(player);
	}

	@EventHandler(ignoreCancelled = true)
	public void onDrag(InventoryDragEvent event) {
		if (!(event.getInventory().getHolder() instanceof RepairHolder)) {
			return;
		}

		for (int slot : event.getRawSlots()) {
			if (slot < event.getInventory().getSize()) {
				event.setCancelled(true);
				return;
			}
		}
	}

	private void handleConfirm(Player player) {
		ItemStack itemInHand = player.getInventory().getItemInMainHand();
		if (itemInHand.getType() == Material.AIR) {
			send(player, "messages.must-hold-item");
			player.closeInventory();
			return;
		}

		if (!service.isRepairable(itemInHand)) {
			send(player, "messages.not-repairable");
			player.closeInventory();
			return;
		}

		if (!service.isDamaged(itemInHand)) {
			send(player, "messages.not-damaged");
			player.closeInventory();
			return;
		}

		ToolRepairService.RepairQuote quote = service.quote(itemInHand);
		if (!service.withdraw(player, quote.finalCost())) {
			send(player, "messages.not-enough-money", Map.of("cost", service.formatMoney(quote.finalCost())));
			player.closeInventory();
			return;
		}

		Damageable damageable = (Damageable) itemInHand.getItemMeta();
		int damageBefore = damageable.getDamage();
		int maxDurability = itemInHand.getType().getMaxDurability();

		service.repair(itemInHand);
		logRepairHistory(player, itemInHand, quote, damageBefore, maxDurability);
		send(player, "messages.item-repaired", Map.of("cost", service.formatMoney(quote.finalCost())));
		player.closeInventory();
	}

	private void logRepairHistory(Player player, ItemStack item, ToolRepairService.RepairQuote quote, int damageBefore, int maxDurability) {
		int x = player.getLocation().getBlockX();
		int y = player.getLocation().getBlockY();
		int z = player.getLocation().getBlockZ();
		String location = player.getWorld().getName() + "@" + x + "," + y + "," + z;
		String itemType = item.getType().name().toLowerCase(Locale.ROOT);

		logger.audit(
			"RepairHistory player=" + player.getName()
				+ " uuid=" + player.getUniqueId()
				+ " item=" + itemType
				+ " durabilityBefore=" + (maxDurability - damageBefore) + "/" + maxDurability
				+ " damageBefore=" + damageBefore
				+ " baseCost=" + service.formatMoney(quote.baseCost())
				+ " damageCost=" + service.formatMoney(quote.damageCost())
				+ " finalCost=" + service.formatMoney(quote.finalCost())
				+ " remainingBefore=" + formatPercent(quote.remainingDurabilityPercent()) + "%"
				+ " location=" + location
		);
	}

	private void fillBackground(Inventory inventory) {
		ItemStack filler = LunaUi.item(
			Material.GRAY_STAINED_GLASS_PANE,
			"<color:" + LunaPalette.NEUTRAL_300 + "> </color>",
			List.of()
		);

		for (int slot = 0; slot < inventory.getSize(); slot++) {
			if (slot == ITEM_SLOT || slot == CONFIRM_SLOT || slot == CANCEL_SLOT) {
				continue;
			}

			inventory.setItem(slot, filler);
		}
	}

	private ItemStack buildReceiptItem(ItemStack source, ToolRepairService.RepairQuote quote) {
		ItemStack receipt = source.clone();
		ItemMeta meta = receipt.getItemMeta();
		List<Component> lore = new ArrayList<>();
		if (meta.hasLore() && meta.lore() != null) {
			lore.addAll(meta.lore());
			lore.add(Component.empty());
		}
		lore.add(Component.empty());

		lore.add(LunaUi.mini("<color:" + LunaPalette.INFO_300 + "><b>🧾 HÓA ĐƠN SỬA DỤNG CỤ</b></color>"));
		lore.add(Component.empty());
		lore.add(LunaUi.mini(receiptLine("Độ bền còn", formatPercent(quote.remainingDurabilityPercent()) + "%", LunaPalette.SKY_300, LunaPalette.GOLD_300)));
		lore.add(LunaUi.mini(receiptLine("Phí sửa gốc", service.formatMoney(quote.baseCost()), LunaPalette.SKY_300, LunaPalette.GOLD_300)));
		lore.add(LunaUi.mini(receiptLine("Phí hư hại", service.formatMoney(quote.damageCost()), LunaPalette.SKY_300, LunaPalette.GOLD_300)));
		lore.add(LunaUi.mini(receiptLine("Tổng phí", service.formatMoney(quote.finalCost()), LunaPalette.SUCCESS_300, LunaPalette.SUCCESS_300)));

		meta.lore(lore);
		receipt.setItemMeta(meta);
		return receipt;
	}

	private String receiptLine(String label, String value, String labelColor, String valueColor) {
		String dots = Formatters.dotLeader(label, false, value, false, RECEIPT_TARGET_WIDTH_PX, ".");

		return "<color:" + labelColor + ">" + label + "</color>"
			+ "<color:" + LunaPalette.NEUTRAL_500 + ">" + dots + "</color>"
			+ "<color:" + valueColor + ">" + value + "</color>";
	}

	private void send(CommandSender sender, String path) {
		send(sender, path, Map.of());
	}

	private void send(CommandSender sender, String path, Map<String, String> placeholders) {
		String text = service.message(path);
		sender.sendMessage(formatter.format(sender, text, placeholders));
	}

	private String formatPercent(double value) {
		return String.format(Locale.US, "%.1f", value);
	}

	private record RepairHolder(UUID playerId) implements InventoryHolder {
		@Override
		public Inventory getInventory() {
			return null;
		}
	}
}
