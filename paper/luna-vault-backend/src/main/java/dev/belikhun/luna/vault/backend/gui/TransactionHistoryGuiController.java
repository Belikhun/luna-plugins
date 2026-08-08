package dev.belikhun.luna.vault.backend.gui;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.backend.service.PaperVaultGateway;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class TransactionHistoryGuiController implements Listener {
	private final org.bukkit.plugin.java.JavaPlugin plugin;
	private final PaperVaultGateway gateway;
	private final ConfigStore coreConfig;
	private final int pageSize;

	public TransactionHistoryGuiController(org.bukkit.plugin.java.JavaPlugin plugin, PaperVaultGateway gateway, ConfigStore coreConfig, int pageSize) {
		this.plugin = plugin;
		this.gateway = gateway;
		this.coreConfig = coreConfig;
		this.pageSize = Math.max(9, pageSize);
	}

	public void open(Player player, int page) {
		gateway.history(player.getUniqueId(), page, pageSize).whenComplete((historyPage, throwable) ->
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				if (!player.isOnline()) {
					return;
				}
				if (throwable != null) {
					player.sendRichMessage("<red>❌ Không thể tải lịch sử giao dịch từ proxy.</red>");
					return;
				}
				openView(player, historyPage);
			})
		);
	}

	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!(event.getInventory().getHolder() instanceof GuiView view)) {
			return;
		}
		if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
			return;
		}

		event.setCancelled(true);
		view.handleClick(player, event);
	}

	private void openView(Player player, VaultTransactionPage page) {
		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("LunaVault", "Lịch sử"));
		List<VaultTransactionRecord> entries = page.entries();
		for (int index = 0; index < entries.size() && index < 45; index++) {
			VaultTransactionRecord entry = entries.get(index);
			boolean outgoing = player.getUniqueId().equals(entry.senderId());
			Material material = outgoing ? Material.RED_DYE : Material.LIME_DYE;
			String title = outgoing ? "<red>⬤ Đã gửi</red>" : "<green>⬤ Đã nhận</green>";
			view.setItem(index, itemFor(player, entry, material, title));
		}

		if (entries.isEmpty()) {
			view.setItem(22, LunaUi.item(Material.PAPER, "<yellow>ℹ Chưa có giao dịch</yellow>", List.of(
				LunaUi.mini("<gray>Lịch sử của bạn đang trống.</gray>"),
				LunaUi.mini("<gray>Hãy thử lại sau khi có giao dịch mới.</gray>")
			)));
		}

		if (page.page() > 0) {
			view.setItem(45, LunaUi.item(Material.ARROW, "<yellow>← Trang trước</yellow>", List.of()), (clicker, event, gui) -> open(clicker, page.page() - 1));
		}
		if (page.page() < page.maxPage()) {
			view.setItem(53, LunaUi.item(Material.ARROW, "<yellow>Trang sau →</yellow>", List.of()), (clicker, event, gui) -> open(clicker, page.page() + 1));
		}
		view.setItem(49, LunaUi.item(Material.BOOK, "<aqua>ℹ Thông tin</aqua>", List.of(
			LunaUi.mini("<white>Tổng giao dịch: <yellow>" + page.totalCount() + "</yellow></white>"),
			LunaUi.mini("<white>Trang hiện tại: <yellow>" + (page.page() + 1) + "/" + (page.maxPage() + 1) + "</yellow></white>")
		)));
		view.setItem(52, LunaUi.item(Material.OAK_DOOR, "<red>Đóng</red>", List.of()), (clicker, event, gui) -> clicker.closeInventory());
		view.open(player);
	}

	private ItemStack itemFor(Player viewer, VaultTransactionRecord entry, Material material, String title) {
		List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
		lore.add(LunaUi.mini("<white>Người gửi: <yellow>" + safeName(entry.senderName()) + "</yellow></white>"));
		lore.add(LunaUi.mini("<white>Người nhận: <yellow>" + safeName(entry.receiverName()) + "</yellow></white>"));
		lore.add(LunaUi.mini("<white>Số tiền: <gold>" + formatMinor(entry.amountMinor()) + "</gold></white>"));
		lore.add(LunaUi.mini("<white>Nguồn: <aqua>" + safeName(entry.source()) + "</aqua></white>"));
		if (entry.details() != null && !entry.details().isBlank()) {
			lore.add(LunaUi.mini("<white>Ghi chú: <gray>" + entry.details() + "</gray></white>"));
		}
		lore.add(LunaUi.mini("<white>Thời gian: <gray>" + Formatters.date(Instant.ofEpochMilli(entry.completedAt())) + "</gray></white>"));
		return LunaUi.item(material, title, lore);
	}

	private String formatMinor(long minor) {
		return Formatters.money(coreConfig, minor, VaultMoney.SCALE);
	}

	private String safeName(String value) {
		return value == null || value.isBlank() ? "Hệ thống" : value;
	}
}
