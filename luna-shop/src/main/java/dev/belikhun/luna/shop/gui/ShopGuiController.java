package dev.belikhun.luna.shop.gui;

import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.gui.LunaPagination;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.ui.LunaLore;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.shop.model.ShopCategory;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.service.ShopResult;
import dev.belikhun.luna.shop.service.ShopService;
import dev.belikhun.luna.shop.store.ShopItemStore;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShopGuiController implements Listener {
	private static final int PAGE_SIZE = 45;
	private static final long CONFIRMATION_TIMEOUT_TICKS = 20L * 15L;
	private static final long ALERT_RESET_TICKS = 20L * 5L;
	private static final int[] QUICK_AMOUNT_SLOTS = {28, 29, 30, 32, 33, 34};
	private static final int[] QUICK_AMOUNTS = {1, 4, 8, 16, 32, 64};
	private static final int[] DECREASE_SLOTS = {36, 37, 38, 39};
	private static final int[] DECREASE_VALUES = {-8, -4, -2, -1};
	private static final int[] INCREASE_SLOTS = {41, 42, 43, 44};
	private static final int[] INCREASE_VALUES = {1, 2, 4, 8};
    private static final int CONFIRM_SLOT = 40;

	private final JavaPlugin plugin;
	private final ShopService service;
	private final ShopItemStore store;
	private final GuiManager guiManager;
	private final PlainTextComponentSerializer plainText;
	private final Map<UUID, SearchRequest> waitingSearch;
	private final Map<UUID, TradeSession> waitingAmount;
	private final Map<UUID, AdminPrompt> waitingAdminPrompt;
	private final Map<UUID, UUID> pendingConfirmations;

	public ShopGuiController(JavaPlugin plugin, ShopService service, ShopItemStore store) {
		this.plugin = plugin;
		this.service = service;
		this.store = store;
		this.guiManager = new GuiManager();
		this.plainText = PlainTextComponentSerializer.plainText();
		this.waitingSearch = new HashMap<>();
		this.waitingAmount = new HashMap<>();
		this.waitingAdminPrompt = new HashMap<>();
		this.pendingConfirmations = new HashMap<>();

		plugin.getServer().getPluginManager().registerEvents(guiManager, plugin);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	public void openManagementMenu(Player player, int page) {
		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý"));
		guiManager.track(view);
		fillFooter(view);

		view.setItem(20, item(
			Material.CHEST,
			"<gold>♦ Quản Lý Danh Mục",
			List.of(
				"<gray>● Quản lý category và icon đại diện.",
				"",
				"<gray>Nhấn để mở."
			)
		), (clicker, event, gui) -> openCategoryManagement(clicker, 0));

		view.setItem(24, item(
			Material.BOOK,
			"<aqua>♦ Quản Lý Mặt Hàng",
			List.of(
				"<gray>● Quản lý item shop, giá và danh mục.",
				"",
				"<gray>Nhấn để mở."
			)
		), (clicker, event, gui) -> openItemManagement(clicker, 0));

		view.setItem(49, nav(Material.EMERALD, "<green>★ Mở Shop người chơi"), (clicker, event, gui) -> openMainMenu(clicker, 0));
		view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> clicker.closeInventory());
		player.openInventory(view.getInventory());
	}

	public void openCategoryManagement(Player player, int page) {
		List<ShopCategory> categories = store.allCategories();
		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Danh Mục"));
		guiManager.track(view);

		int maxPage = maxPage(categories.size());
		int currentPage = clampPage(page, maxPage);
		int start = currentPage * PAGE_SIZE;
		int end = Math.min(categories.size(), start + PAGE_SIZE);
		for (int i = start; i < end; i++) {
			ShopCategory category = categories.get(i);
			int slot = i - start;
			ItemStack icon = category.iconItem().clone();
			ItemMeta meta = icon.getItemMeta();
			meta.displayName(mm("<gold>♦ </gold>" + displayCategory(category.id())));
			meta.lore(appendShopLore(meta, List.of(
				"<gray>№ <white>" + category.id(),
				"<gray>ℹ Hiển thị: " + displayCategory(category.id()),
				"<gray>♦ Số mặt hàng: <green>" + store.byCategory(category.id()).size(),
				"",
				"<gray>▶ Chuột trái: <green>Đổi icon từ item cầm tay</green>",
				"<gray>▶ Chuột phải: <yellow>Đổi tên danh mục</yellow>",
				"<gray>▶ Shift + phải: <red>Xóa danh mục (phải rỗng)</red>"
			)));
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
			icon.setItemMeta(meta);

			view.setItem(slot, icon, (clicker, event, gui) -> {
				if (event.isShiftClick() && event.isRightClick()) {
					if (!store.deleteCategory(category.id(), null)) {
						clicker.sendMessage(mm("<red>❌ Danh mục còn item, không thể xóa trực tiếp.</red>"));
						return;
					}

					clicker.sendMessage(mm("<green>✔ Đã xóa danh mục <white>" + category.id() + "</white>.</green>"));
					openCategoryManagement(clicker, currentPage);
					return;
				}

				if (event.isRightClick()) {
					beginAdminPrompt(clicker, AdminPromptType.RENAME_CATEGORY, category.id(), null, currentPage);
					return;
				}

				ItemStack hand = clicker.getInventory().getItemInMainHand();
				if (hand.getType().isAir()) {
					clicker.sendMessage(mm("<red>❌ Hãy cầm item trên tay để đặt icon danh mục.</red>"));
					return;
				}

				store.upsertCategoryIcon(category.id(), hand);
				clicker.sendMessage(mm("<green>✔ Đã cập nhật icon cho danh mục <white>" + category.id() + "</white>.</green>"));
				openCategoryManagement(clicker, currentPage);
			});
		}

		fillFooter(view);
		if (currentPage > 0) {
			view.setItem(45, nav(Material.ARROW, "<yellow>← Trang trước"), (clicker, event, gui) -> openCategoryManagement(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			view.setItem(53, nav(Material.ARROW, "<yellow>Trang sau →"), (clicker, event, gui) -> openCategoryManagement(clicker, currentPage + 1));
		}

		view.setItem(49, nav(Material.ANVIL, "<aqua>➕ Tạo danh mục mới"), (clicker, event, gui) -> beginAdminPrompt(clicker, AdminPromptType.CREATE_CATEGORY, null, null, currentPage));
		view.setItem(50, nav(Material.BOOK, "<yellow>Quản lý mặt hàng"), (clicker, event, gui) -> openItemManagement(clicker, 0));
		view.setItem(52, nav(Material.ARROW, "<yellow>← Quay lại"), (clicker, event, gui) -> openManagementMenu(clicker, 0));
		player.openInventory(view.getInventory());
	}

	public void openItemManagement(Player player, int page) {
		List<ShopItem> items = store.all();
		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Mặt Hàng"));
		guiManager.track(view);

		int maxPage = maxPage(items.size());
		int currentPage = clampPage(page, maxPage);
		int start = currentPage * PAGE_SIZE;
		int end = Math.min(items.size(), start + PAGE_SIZE);
		for (int i = start; i < end; i++) {
			ShopItem item = items.get(i);
			int slot = i - start;
			ItemStack icon = item.itemStack().clone();
			ItemMeta meta = icon.getItemMeta();
			meta.lore(appendShopLore(meta, List.of(
				"<gray>№ <white>" + item.id(),
				"<gray>♦ Danh mục: " + displayCategory(item.category()),
				"<green>💰 Giá mua: <gold>" + service.formatMoney(item.buyPrice()),
				"<yellow>💵 Giá bán: <gold>" + service.formatMoney(item.sellPrice()),
				"",
				"<gray>▶ Chuột trái: <green>Cập nhật item từ tay</green>",
				"<gray>▶ Chuột phải: <red>Xóa item</red>",
				"<gray>▶ Shift + trái: <aqua>Sửa giá (chat)</aqua>",
				"<gray>▶ Shift + phải: <yellow>Đổi danh mục (chat)</yellow>"
			)));
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
			icon.setItemMeta(meta);

			view.setItem(slot, icon, (clicker, event, gui) -> {
				if (event.isShiftClick() && event.isRightClick()) {
					beginAdminPrompt(clicker, AdminPromptType.MOVE_ITEM_CATEGORY, item.id(), null, currentPage);
					return;
				}

				if (event.isShiftClick() && event.isLeftClick()) {
					beginAdminPrompt(clicker, AdminPromptType.EDIT_ITEM_PRICES, item.id(), null, currentPage);
					return;
				}

				if (event.isRightClick()) {
					openConfirmationDialog(
						clicker,
						"<red>⚠ Xác nhận xóa mặt hàng",
						List.of(
							"<gray>Bạn sắp xóa item shop:",
							"<white>" + item.id(),
							"",
							"<red>Hành động này không thể hoàn tác."
						),
						() -> {
							store.remove(item.id());
							clicker.sendMessage(mm("<green>✔ Đã xóa item <white>" + item.id() + "</white> khỏi shop.</green>"));
							openItemManagement(clicker, currentPage);
						},
						() -> openItemManagement(clicker, currentPage)
					);
					return;
				}

				ItemStack hand = clicker.getInventory().getItemInMainHand();
				if (hand.getType().isAir()) {
					clicker.sendMessage(mm("<red>❌ Hãy cầm item trên tay để cập nhật.</red>"));
					return;
				}

				ShopItem updated = ShopItem.fromItemStack(item.id(), item.category(), item.buyPrice(), item.sellPrice(), hand);
				store.upsert(updated);
				clicker.sendMessage(mm("<green>✔ Đã cập nhật item <white>" + item.id() + "</white>.</green>"));
				openItemManagement(clicker, currentPage);
			});
		}

		fillFooter(view);
		if (currentPage > 0) {
			view.setItem(45, nav(Material.ARROW, "<yellow>← Trang trước"), (clicker, event, gui) -> openItemManagement(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			view.setItem(53, nav(Material.ARROW, "<yellow>Trang sau →"), (clicker, event, gui) -> openItemManagement(clicker, currentPage + 1));
		}

		view.setItem(49, nav(Material.ANVIL, "<aqua>➕ Tạo item mới"), (clicker, event, gui) -> beginAdminPrompt(clicker, AdminPromptType.CREATE_ITEM, null, null, currentPage));
		view.setItem(50, nav(Material.CHEST, "<yellow>Quản lý danh mục"), (clicker, event, gui) -> openCategoryManagement(clicker, 0));
		view.setItem(52, nav(Material.ARROW, "<yellow>← Quay lại"), (clicker, event, gui) -> openManagementMenu(clicker, 0));
		player.openInventory(view.getInventory());
	}

	public void openMainMenu(Player player, int page) {
		List<ShopCategory> categories = store.allCategories();

		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Cửa Hàng"));
		guiManager.track(view);

		int maxPage = maxPage(categories.size());
		int currentPage = clampPage(page, maxPage);
		int start = currentPage * PAGE_SIZE;
		int end = Math.min(categories.size(), start + PAGE_SIZE);
		for (int i = start; i < end; i++) {
			ShopCategory category = categories.get(i);
			int slot = i - start;
			int count = store.byCategory(category.id()).size();
			ItemStack icon = category.iconItem().clone();
			ItemMeta meta = icon.getItemMeta();
			meta.displayName(mm("<gold>♦ </gold>" + displayCategory(category.id())));
			meta.lore(appendShopLore(meta, List.of(
				"<gray>♦ Số mặt hàng: <green>" + count,
				"<gray>ℹ Nhấn để mở danh mục này."
			)));
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
			icon.setItemMeta(meta);
			view.setItem(slot, icon, (clicker, event, gui) -> openCategoryMenu(clicker, category.id(), 0));
		}

		fillFooter(view);
		if (currentPage > 0) {
			view.setItem(45, nav(Material.ARROW, "<yellow>← Trang trước"), (clicker, event, gui) -> openMainMenu(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			view.setItem(53, nav(Material.ARROW, "<yellow>Trang sau →"), (clicker, event, gui) -> openMainMenu(clicker, currentPage + 1));
		}
		view.setItem(49, nav(Material.COMPASS, "<aqua>🔍 Tìm kiếm mặt hàng"), (clicker, event, gui) -> beginSearch(clicker, null));
		view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> clicker.closeInventory());

		player.openInventory(view.getInventory());
	}

	public void openCategoryMenu(Player player, String category, int page) {
		List<ShopItem> items = store.byCategory(category);
		items.sort(Comparator.comparing(ShopItem::id));

		BrowseContext context = BrowseContext.category(category, page);
		openItemList(player, items, LunaUi.guiTitleBreadcrumb("Luna Shop", "Danh Mục", prettyCategory(category)), context);
	}

	public void openSearchMenu(Player player, String query, int page) {
		List<ShopItem> items = store.search(query);
		items.sort(Comparator.comparing(ShopItem::id));

		BrowseContext context = BrowseContext.search(query, page);
		openItemList(player, items, LunaUi.guiTitleBreadcrumb("Luna Shop", "Tìm Kiếm", query), context);
	}

	private void openItemList(Player player, List<ShopItem> items, Component title, BrowseContext context) {
		GuiView view = new GuiView(54, title);
		guiManager.track(view);

		int maxPage = maxPage(items.size());
		int currentPage = clampPage(context.page(), maxPage);
		int start = currentPage * PAGE_SIZE;
		int end = Math.min(items.size(), start + PAGE_SIZE);
		for (int i = start; i < end; i++) {
			ShopItem shopItem = items.get(i);
			int slot = i - start;
			ItemStack icon = shopItem.itemStack().clone();
			ItemMeta meta = icon.getItemMeta();
			meta.lore(appendShopLore(meta, List.of(
				"<gray>№ <white>" + shopItem.id(),
				"<gray>♦ Danh mục: " + displayCategory(shopItem.category()),
				"<green>💰 Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()),
				"<yellow>💵 Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice()),
				"",
				"<gray>▶ Chuột trái: <green>Mua</green>",
				"<gray>▶ Chuột phải: <yellow>Bán</yellow>"
			)));
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
			icon.setItemMeta(meta);

			view.setItem(slot, icon, (clicker, event, gui) -> {
				TradeMode mode = event.isRightClick() ? TradeMode.SELL : TradeMode.BUY;
				openTradeMenu(clicker, new TradeSession(shopItem.id(), mode, 1, context.withPage(currentPage)));
			});
		}

		fillFooter(view);
		if (currentPage > 0) {
			view.setItem(45, nav(Material.ARROW, "<yellow>← Trang trước"), (clicker, event, gui) -> openItemList(clicker, items, title, context.withPage(currentPage - 1)));
		}
		if (currentPage < maxPage) {
			view.setItem(53, nav(Material.ARROW, "<yellow>Trang sau →"), (clicker, event, gui) -> openItemList(clicker, items, title, context.withPage(currentPage + 1)));
		}

		if (context.search()) {
			view.setItem(49, nav(Material.COMPASS, "<aqua>🔍 Tìm kiếm mới"), (clicker, event, gui) -> beginSearch(clicker, null));
			view.setItem(50, nav(Material.CHEST, "<yellow>Quay về danh mục"), (clicker, event, gui) -> openMainMenu(clicker, 0));
		} else {
			view.setItem(49, nav(Material.COMPASS, "<aqua>🔍 Tìm trong danh mục"), (clicker, event, gui) -> beginSearch(clicker, context.category()));
			view.setItem(50, nav(Material.CHEST, "<yellow>Danh mục chính"), (clicker, event, gui) -> openMainMenu(clicker, 0));
		}
		view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> clicker.closeInventory());

		player.openInventory(view.getInventory());
	}

	private void openTradeMenu(Player player, TradeSession session) {
		ShopItem shopItem = store.find(session.itemId()).orElse(null);
		if (shopItem == null) {
			player.sendMessage(mm("<red>❌ Không tìm thấy vật phẩm này trong shop.</red>"));
			openMainMenu(player, 0);
			return;
		}

		int amount = clampAmount(session.amount());
		TradeSession normalized = session.withAmount(amount);
		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Giao Dịch"));
		guiManager.track(view);

		fillFooter(view);

		ItemStack display = shopItem.itemStack().clone();
		ItemMeta meta = display.getItemMeta();
		double buyTotal = shopItem.buyPrice() * amount;
		double sellTotal = shopItem.sellPrice() * amount;
		meta.lore(appendShopLore(meta, List.of(
			"<white>№ <yellow>" + shopItem.id(),
			"<white>♦ Danh mục: " + displayCategory(shopItem.category()),
			"<white>♦ Số lượng: <yellow>" + amount,
			"<green>💰 Tổng mua: <gold>" + service.formatMoney(buyTotal),
			"<yellow>💵 Tổng bán: <gold>" + service.formatMoney(sellTotal),
			"",
			"<white>💰 Số dư hiện tại: <yellow>" + service.formatMoney(service.economy().balance(player))
		)));
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		display.setItemMeta(meta);
		view.setItem(13, display);

		view.setItem(20, modeButton(TradeMode.BUY, normalized.mode() == TradeMode.BUY), (clicker, event, gui) -> openTradeMenu(clicker, normalized.withMode(TradeMode.BUY)));
		view.setItem(24, modeButton(TradeMode.SELL, normalized.mode() == TradeMode.SELL), (clicker, event, gui) -> openTradeMenu(clicker, normalized.withMode(TradeMode.SELL)));

		for (int i = 0; i < QUICK_AMOUNTS.length; i++) {
			int amountValue = QUICK_AMOUNTS[i];
			int quickSlot = QUICK_AMOUNT_SLOTS[i];
			ItemStack quickButtonItem = amountButton(shopItem, normalized.mode(), amountValue);
			view.setItem(quickSlot, quickButtonItem, (clicker, event, gui) -> {
				TradeSession quickSession = normalized.withAmount(amountValue);
				if (quickSession.mode() == TradeMode.BUY && !hasEnoughMoney(clicker, shopItem, quickSession.amount())) {
					showInsufficientAlert(clicker, gui, quickSlot, quickSession.mode(), shopItem, quickSession.amount(), quickButtonItem);
					return;
				}

				if (quickSession.mode() == TradeMode.SELL && !hasEnoughItems(clicker, shopItem, quickSession.amount())) {
					showInsufficientAlert(clicker, gui, quickSlot, quickSession.mode(), shopItem, quickSession.amount(), quickButtonItem);
					return;
				}

				handleTradeConfirm(clicker, shopItem, quickSession, gui, quickSlot, quickButtonItem);
			});
		}

		for (int i = 0; i < DECREASE_VALUES.length; i++) {
			int delta = DECREASE_VALUES[i];
			view.setItem(DECREASE_SLOTS[i], adjustButton(delta), (clicker, event, gui) -> openTradeMenu(clicker, normalized.withAmount(clampAmount(normalized.amount() + delta))));
		}

		for (int i = 0; i < INCREASE_VALUES.length; i++) {
			int delta = INCREASE_VALUES[i];
			view.setItem(INCREASE_SLOTS[i], adjustButton(delta), (clicker, event, gui) -> openTradeMenu(clicker, normalized.withAmount(clampAmount(normalized.amount() + delta))));
		}

		ItemStack confirmItem = confirmButton(shopItem, normalized.mode(), amount);
		view.setItem(CONFIRM_SLOT, confirmItem, (clicker, event, gui) -> handleTradeConfirm(clicker, shopItem, normalized, gui, CONFIRM_SLOT, confirmItem));

		view.setItem(49, item(Material.NAME_TAG, "<aqua>✦ Nhập thủ công", List.of(
			line(LunaPalette.INFO_500, "ℹ Tự nhập số lượng muốn giao dịch"),
			"",
			line(LunaPalette.NEUTRAL_100, "Ví dụ: <white>96</white>, <white>256</white>, <white>1024</white>"),
			plainLine(LunaPalette.NEUTRAL_100, "Gõ <white>huy</white> để hủy")
		)), (clicker, event, gui) -> beginManualAmount(clicker, normalized));
		view.setItem(45, item(Material.ARROW, "<yellow>← Quay lại", List.of(
			plainLine(LunaPalette.WARNING_500, "Quay về danh sách trước"),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Số lượng vừa chọn vẫn giữ")
		)), (clicker, event, gui) -> returnToBrowse(clicker, normalized.context()));

		if (normalized.mode() == TradeMode.SELL) {
			int quickSellAllAmount = service.countSimilar(player.getInventory(), shopItem.itemStack());
			ItemStack quickSellAllItem = quickSellAllButton(shopItem, quickSellAllAmount);
			view.setItem(23, quickSellAllItem, (clicker, event, gui) -> {
				int sellAmount = service.countSimilar(clicker.getInventory(), shopItem.itemStack());
				if (sellAmount <= 0) {
					clicker.sendMessage(mm("<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>"));
					showInsufficientAlert(clicker, gui, 23, TradeMode.SELL, shopItem, 1, quickSellAllItem);
					return;
				}

				double expected = shopItem.sellPrice() * sellAmount;
				openConfirmationDialog(
					clicker,
					"<yellow>⚠ Xác nhận bán nhanh toàn bộ",
					List.of(
						"<gray>Vật phẩm: <white>" + shopItem.id(),
						"<gray>Số lượng sẽ bán: <white>" + sellAmount,
						"<gray>Tiền dự kiến nhận: <gold>" + service.formatMoney(expected)
					),
					() -> {
						ShopResult result = service.sellAllSimilar(clicker, shopItem);
						clicker.sendMessage(mm(result.message()));
						openTradeMenu(clicker, normalized);
					},
					() -> openTradeMenu(clicker, normalized)
				);
			});
		} else {
			view.setItem(23, item(Material.GRAY_DYE, "<white>★ Bán nhanh tất cả", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Chỉ dùng trong chế độ <yellow>Bán</yellow>"),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Đổi mode để bán nhanh hơn")
			)));
		}

		view.setItem(52, item(Material.OAK_DOOR, "<red>Đóng", List.of(
			plainLine(LunaPalette.DANGER_500, "Thoát giao diện giao dịch")
		)), (clicker, event, gui) -> clicker.closeInventory());
		player.openInventory(view.getInventory());
	}

	private void handleTradeConfirm(Player player, ShopItem shopItem, TradeSession session, GuiView view, int clickedSlot, ItemStack restoreItem) {
		if (session.mode() == TradeMode.BUY && !hasEnoughMoney(player, shopItem, session.amount())) {
			showInsufficientAlert(player, view, clickedSlot, session.mode(), shopItem, session.amount(), restoreItem);
			return;
		}

		if (session.mode() == TradeMode.SELL && !hasEnoughItems(player, shopItem, session.amount())) {
			showInsufficientAlert(player, view, clickedSlot, session.mode(), shopItem, session.amount(), restoreItem);
			return;
		}

		if (session.mode() == TradeMode.BUY) {
			double total = shopItem.buyPrice() * session.amount();
			double balance = service.economy().balance(player);
			if (balance > 0D && total > balance * 0.5D) {
				openConfirmationDialog(
					player,
					"<yellow>⚠ Xác nhận mua đơn lớn",
					List.of(
						"<gray>Tổng tiền: <gold>" + service.formatMoney(total),
						"<gray>Số dư hiện tại: <white>" + service.formatMoney(balance),
						"<gray>Lệnh mua này vượt <white>50%</white> số dư của bạn."
					),
					() -> {
						ShopResult result = service.buy(player, shopItem, session.amount());
						player.sendMessage(mm(result.message()));
						openTradeMenu(player, session);
					},
					() -> openTradeMenu(player, session)
				);
				return;
			}
		}

		ShopResult result = session.mode() == TradeMode.BUY
			? service.buy(player, shopItem, session.amount())
			: service.sell(player, shopItem, session.amount());
		player.sendMessage(mm(result.message()));
		openTradeMenu(player, session);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onChatInput(AsyncChatEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();

		if (waitingSearch.containsKey(uuid)) {
			event.setCancelled(true);
			SearchRequest request = waitingSearch.remove(uuid);
			String query = plainText.serialize(event.message()).trim();
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				if (query.isBlank() || query.equalsIgnoreCase("huy") || query.equalsIgnoreCase("cancel")) {
					player.sendMessage(mm("<yellow>ℹ Đã hủy tìm kiếm.</yellow>"));
					if (request.category() == null) {
						openMainMenu(player, 0);
					} else {
						openCategoryMenu(player, request.category(), 0);
					}
					return;
				}

				if (request.category() == null) {
					openSearchMenu(player, query, 0);
					return;
				}

				List<ShopItem> items = store.searchInCategory(request.category(), query);
				BrowseContext context = BrowseContext.categorySearch(request.category(), query, 0);
				openItemList(player, items, LunaUi.guiTitleBreadcrumb("Luna Shop", "Tìm Kiếm", prettyCategory(request.category()), query), context);
			});
			return;
		}

		if (waitingAmount.containsKey(uuid)) {
			event.setCancelled(true);
			TradeSession session = waitingAmount.remove(uuid);
			String input = plainText.serialize(event.message()).trim();
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				if (input.isBlank() || input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel")) {
					player.sendMessage(mm("<yellow>ℹ Đã hủy nhập số lượng.</yellow>"));
					openTradeMenu(player, session);
					return;
				}

				try {
					int value = Integer.parseInt(input);
					if (value <= 0) {
						player.sendMessage(mm("<red>❌ Số lượng phải lớn hơn 0.</red>"));
						openTradeMenu(player, session);
						return;
					}

					openTradeMenu(player, session.withAmount(clampAmount(value)));
				} catch (NumberFormatException exception) {
					player.sendMessage(mm("<red>❌ Giá trị không hợp lệ. Vui lòng nhập số nguyên.</red>"));
					openTradeMenu(player, session);
				}
			});
		}

		if (waitingAdminPrompt.containsKey(uuid)) {
			event.setCancelled(true);
			AdminPrompt prompt = waitingAdminPrompt.remove(uuid);
			String input = plainText.serialize(event.message()).trim();
			plugin.getServer().getScheduler().runTask(plugin, () -> handleAdminPrompt(player, prompt, input));
		}
	}

	private void handleAdminPrompt(Player player, AdminPrompt prompt, String input) {
		if (input.isBlank() || input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel")) {
			player.sendMessage(mm("<yellow>ℹ Đã hủy thao tác quản trị.</yellow>"));
			returnToAdminMenu(player, prompt);
			return;
		}

		switch (prompt.type()) {
			case CREATE_CATEGORY -> {
				if (store.findCategory(input).isPresent()) {
					player.sendMessage(mm("<red>❌ Danh mục đã tồn tại.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				ItemStack hand = player.getInventory().getItemInMainHand();
				if (hand.getType().isAir()) {
					player.sendMessage(mm("<red>❌ Hãy cầm item để làm icon danh mục.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				store.upsertCategory(ShopCategory.fromIcon(input, hand));
				player.sendMessage(mm("<green>✔ Đã tạo danh mục <white>" + ShopItem.normalizeCategory(input) + "</white>.</green>"));
				returnToAdminMenu(player, prompt);
			}
			case RENAME_CATEGORY -> {
				if (store.findCategory(input).isPresent()) {
					player.sendMessage(mm("<red>❌ Danh mục đích đã tồn tại.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				if (!store.renameCategory(prompt.primary(), input)) {
					player.sendMessage(mm("<red>❌ Không thể đổi tên danh mục.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				player.sendMessage(mm("<green>✔ Đã đổi tên danh mục thành <white>" + ShopItem.normalizeCategory(input) + "</white>.</green>"));
				returnToAdminMenu(player, prompt);
			}
			case CREATE_ITEM -> {
				String[] parts = input.split("\\s+");
				if (parts.length < 3) {
					player.sendMessage(mm("<red>❌ Sai cú pháp. </red>" + CommandStrings.usage("/shopadmin", CommandStrings.literal("add"), CommandStrings.required("category", "text"), CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number"))));
					returnToAdminMenu(player, prompt);
					return;
				}

				String category = parts[0];
				if (store.findCategory(category).isEmpty()) {
					player.sendMessage(mm("<red>❌ Category không tồn tại. Tạo category trước.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				double buyPrice;
				double sellPrice;
				try {
					buyPrice = Double.parseDouble(parts[1]);
					sellPrice = Double.parseDouble(parts[2]);
				} catch (NumberFormatException exception) {
					player.sendMessage(mm("<red>❌ Giá phải là số hợp lệ.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				if (buyPrice < 0 || sellPrice < 0) {
					player.sendMessage(mm("<red>❌ Giá không được âm.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				ItemStack hand = player.getInventory().getItemInMainHand();
				if (hand.getType().isAir()) {
					player.sendMessage(mm("<red>❌ Hãy cầm item trên tay để tạo mặt hàng.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				var duplicate = store.findBySimilarItem(hand);
				if (duplicate.isPresent()) {
					player.sendMessage(mm("<red>❌ Item này đã có trong shop với id <white>" + duplicate.get().id() + "</white>.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				ShopItem created = ShopItem.fromItemStackAutoId(category, buyPrice, sellPrice, hand);
				store.upsert(created);
				player.sendMessage(mm("<green>✔ Đã tạo mặt hàng <white>" + created.id() + "</white>.</green>"));
				returnToAdminMenu(player, prompt);
			}
			case EDIT_ITEM_PRICES -> {
				ShopItem item = store.find(prompt.primary()).orElse(null);
				if (item == null) {
					player.sendMessage(mm("<red>❌ Item không tồn tại.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				String[] parts = input.split("\\s+");
				if (parts.length < 2) {
					player.sendMessage(mm("<color:" + LunaPalette.WARNING_500 + ">ℹ Dùng:</color> " + CommandStrings.arguments(CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number"))));
					returnToAdminMenu(player, prompt);
					return;
				}

				double buyPrice;
				double sellPrice;
				try {
					buyPrice = Double.parseDouble(parts[0]);
					sellPrice = Double.parseDouble(parts[1]);
				} catch (NumberFormatException exception) {
					player.sendMessage(mm("<red>❌ Giá phải là số hợp lệ.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				if (buyPrice < 0 || sellPrice < 0) {
					player.sendMessage(mm("<red>❌ Giá không được âm.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				store.upsert(new ShopItem(item.id(), item.category(), buyPrice, sellPrice, item.itemData()));
				player.sendMessage(mm("<green>✔ Đã cập nhật giá cho item <white>" + item.id() + "</white>.</green>"));
				returnToAdminMenu(player, prompt);
			}
			case MOVE_ITEM_CATEGORY -> {
				ShopItem item = store.find(prompt.primary()).orElse(null);
				if (item == null) {
					player.sendMessage(mm("<red>❌ Item không tồn tại.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				if (store.findCategory(input).isEmpty()) {
					player.sendMessage(mm("<red>❌ Category không tồn tại.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				store.upsert(new ShopItem(item.id(), input, item.buyPrice(), item.sellPrice(), item.itemData()));
				player.sendMessage(mm("<green>✔ Đã chuyển item <white>" + item.id() + "</white> sang category <white>" + ShopItem.normalizeCategory(input) + "</white>.</green>"));
				returnToAdminMenu(player, prompt);
			}
		}
	}

	private void beginAdminPrompt(Player player, AdminPromptType type, String primary, String secondary, int page) {
		waitingAdminPrompt.put(player.getUniqueId(), new AdminPrompt(type, primary, secondary, page));
		player.closeInventory();
		switch (type) {
			case CREATE_CATEGORY -> player.sendMessage(mm("<aqua>✦ Nhập <white>ID danh mục</white> trên chat. Gõ <white>huy</white> để hủy.</aqua>"));
			case RENAME_CATEGORY -> player.sendMessage(mm("<aqua>✦ Nhập <white>ID mới</white> cho danh mục <white>" + primary + "</white>. Gõ <white>huy</white> để hủy.</aqua>"));
			case CREATE_ITEM -> player.sendMessage(mm("<aqua>✦ Nhập theo mẫu: </aqua>" + CommandStrings.syntax("/shopadmin", CommandStrings.literal("add"), CommandStrings.required("category", "text"), CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number")) + "<gray>. Gõ <white>huy</white> để hủy.</gray>"));
			case EDIT_ITEM_PRICES -> player.sendMessage(mm("<aqua>✦ Nhập giá mới cho <white>" + primary + "</white>: </aqua>" + CommandStrings.arguments(CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number")) + "<gray>. Gõ <white>huy</white> để hủy.</gray>"));
			case MOVE_ITEM_CATEGORY -> player.sendMessage(mm("<aqua>✦ Nhập category mới cho <white>" + primary + "</white>. Gõ <white>huy</white> để hủy.</aqua>"));
		}
	}

	private void returnToAdminMenu(Player player, AdminPrompt prompt) {
		switch (prompt.type()) {
			case CREATE_CATEGORY, RENAME_CATEGORY -> openCategoryManagement(player, prompt.page());
			case CREATE_ITEM, EDIT_ITEM_PRICES, MOVE_ITEM_CATEGORY -> openItemManagement(player, prompt.page());
		}
	}

	private void beginSearch(Player player, String category) {
		waitingSearch.put(player.getUniqueId(), new SearchRequest(category));
		player.closeInventory();
		if (category == null) {
			player.sendMessage(mm("<aqua>🔍 Nhập từ khóa tìm kiếm toàn shop. Gõ <white>huy</white> để hủy.</aqua>"));
			return;
		}

		player.sendMessage(mm("<aqua>🔍 Nhập từ khóa trong danh mục " + displayCategory(category) + "<aqua>. Gõ <white>huy</white> để hủy.</aqua>"));
	}

	private void beginManualAmount(Player player, TradeSession session) {
		waitingAmount.put(player.getUniqueId(), session);
		player.closeInventory();
		player.sendMessage(mm("<aqua>✦ Nhập số lượng tùy chỉnh trên chat. Gõ <white>huy</white> để hủy.</aqua>"));
	}

	private void returnToBrowse(Player player, BrowseContext context) {
		if (context.search()) {
			if (context.category() != null && context.query() != null) {
				List<ShopItem> items = store.searchInCategory(context.category(), context.query());
				openItemList(player, items, LunaUi.guiTitleBreadcrumb("Luna Shop", "Tìm Kiếm", prettyCategory(context.category()), context.query()), context);
				return;
			}

			openSearchMenu(player, context.query() == null ? "" : context.query(), context.page());
			return;
		}

		if (context.category() != null) {
			openCategoryMenu(player, context.category(), context.page());
			return;
		}

		openMainMenu(player, context.page());
	}

	private void fillTradeBody(GuiView view) {
		ItemStack spacer = item(Material.BLACK_STAINED_GLASS_PANE, "<gray> ", List.of());
		for (int slot = 0; slot < 54; slot++) {
			if (slot == 20 || slot == 22 || slot == 23 || slot == 24 || slot == 28 || slot == 29 || slot == 30 || slot == 31 || slot == 32 || slot == 33 || slot == 34 || slot == 37 || slot == 38 || slot == 39 || slot == 40 || slot == 41 || slot == 42 || slot == 43 || slot == 44 || slot >= 45) {
				continue;
			}

			view.setItem(slot, spacer);
		}
	}

	private ItemStack confirmButton(ShopItem shopItem, TradeMode mode, int amount) {
		int displayAmount = Math.max(1, Math.min(64, amount));
		double total = mode == TradeMode.BUY ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;
		if (mode == TradeMode.BUY) {
			ItemStack stack = item(Material.EMERALD, "<green>✔ Xác nhận mua", List.of(
				line(LunaPalette.SUCCESS_500, "♦ Số lượng: <white>" + amount),
				line(LunaPalette.SUCCESS_500, "💰 Tổng thanh toán: <gold>" + service.formatMoney(total)),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Nhấn để mua ngay")
			));
			stack.setAmount(displayAmount);
			return stack;
		}

		ItemStack stack = item(Material.GOLD_INGOT, "<yellow>✔ Xác nhận bán", List.of(
			line(LunaPalette.WARNING_500, "♦ Số lượng: <white>" + amount),
			line(LunaPalette.WARNING_500, "💵 Tổng nhận: <gold>" + service.formatMoney(total)),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Nhấn để bán ngay"),
			plainLine(LunaPalette.NEUTRAL_100, "số lượng đã chọn")
		));
		stack.setAmount(displayAmount);
		return stack;
	}

	private ItemStack modeButton(TradeMode mode, boolean active) {
		if (mode == TradeMode.BUY) {
			return item(active ? Material.LIME_DYE : Material.GRAY_DYE, active ? "<green>▶ Chế độ Mua" : "<white>Chuyển sang Mua", List.of(
				active
					? line(LunaPalette.SUCCESS_500, "Đang ở chế độ mua vật phẩm")
					: plainLine(LunaPalette.NEUTRAL_100, "Nhấn để đổi sang mua"),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Mua theo số lượng bên dưới")
			));
		}

		return item(active ? Material.ORANGE_DYE : Material.GRAY_DYE, active ? "<yellow>▶ Chế độ Bán" : "<white>Chuyển sang Bán", List.of(
			active
				? line(LunaPalette.WARNING_500, "Đang ở chế độ bán vật phẩm")
				: plainLine(LunaPalette.NEUTRAL_100, "Nhấn để đổi sang bán"),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Bán theo số lượng bên dưới")
		));
	}

	private ItemStack amountButton(ShopItem shopItem, TradeMode mode, int amount) {
		double total = mode == TradeMode.BUY ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;
		int displayAmount = Math.max(1, Math.min(64, amount));
		String actionText = mode == TradeMode.BUY
			? plainLine(LunaPalette.NEUTRAL_100, "Nhấn để <green>mua ngay</green>")
			: plainLine(LunaPalette.NEUTRAL_100, "Nhấn để <yellow>bán ngay</yellow>");
		if (mode == TradeMode.BUY) {
			ItemStack stack = item(Material.PAPER, "<aqua>Mua nhanh <white>" + amount, List.of(
				line(LunaPalette.SUCCESS_500, "♦ Số lượng: <white>" + amount),
				line(LunaPalette.SUCCESS_500, "💰 Tổng thanh toán: <gold>" + service.formatMoney(total)),
				"",
				actionText,
				plainLine(LunaPalette.NEUTRAL_100, "số lượng này")
			));
			stack.setAmount(displayAmount);
			return stack;
		}

		ItemStack stack = item(Material.PAPER, "<yellow>Bán nhanh <white>" + amount, List.of(
			line(LunaPalette.WARNING_500, "♦ Số lượng: <white>" + amount),
			line(LunaPalette.WARNING_500, "💵 Tổng nhận: <gold>" + service.formatMoney(total)),
			"",
			actionText,
			plainLine(LunaPalette.NEUTRAL_100, "số lượng này")
		));
		stack.setAmount(displayAmount);
		return stack;
	}

	private ItemStack adjustButton(int delta) {
		String prefix = delta > 0 ? "+" : "";
		Material material = delta > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
		String modeText = delta > 0 ? "Tăng" : "Giảm";
		ItemStack stack = item(material, "<white>" + prefix + delta, List.of(
			line(delta > 0 ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500, "♦ " + modeText + " thêm <white>" + Math.abs(delta)),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Nhấn nhiều lần để chỉnh"),
			plainLine(LunaPalette.NEUTRAL_100, "nhanh hơn")
		));
		stack.setAmount(Math.max(1, Math.min(64, Math.abs(delta))));
		return stack;
	}

	private ItemStack quickSellAllButton(ShopItem shopItem, int sellAmount) {
		double expected = shopItem.sellPrice() * Math.max(0, sellAmount);
		if (sellAmount <= 0) {
			return item(Material.HOPPER, "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
				line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>0"),
				line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(0)),
				"",
				plainLine(LunaPalette.WARNING_500, "⚠ Bạn chưa có item tương tự"),
				plainLine(LunaPalette.WARNING_500, "trong túi đồ")
			));
		}

		return item(Material.HOPPER, "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
			line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>" + sellAmount),
			line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(expected)),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Nhấn để xác nhận"),
			plainLine(LunaPalette.NEUTRAL_100, "bán toàn bộ")
		));
	}

	private boolean hasEnoughMoney(Player player, ShopItem shopItem, int amount) {
		double total = shopItem.buyPrice() * amount;
		return service.economy().balance(player) >= total;
	}

	private boolean hasEnoughItems(Player player, ShopItem shopItem, int amount) {
		return service.countSimilar(player.getInventory(), shopItem.itemStack()) >= amount;
	}

	private void showInsufficientAlert(Player player, GuiView view, int slot, TradeMode mode, ShopItem shopItem, int amount, ItemStack restoreItem) {
		if (mode == TradeMode.BUY) {
			double total = shopItem.buyPrice() * amount;
			double balance = service.economy().balance(player);
			view.setItem(slot, item(Material.ORANGE_STAINED_GLASS_PANE, "<color:" + LunaPalette.WARNING_500 + ">⚠ Không đủ tiền</color>", List.of(
				line(LunaPalette.WARNING_500, "💰 Cần: <gold>" + service.formatMoney(total)),
				line(LunaPalette.WARNING_500, "💰 Hiện có: <white>" + service.formatMoney(balance))
			)));
			player.sendMessage(mm("<color:" + LunaPalette.WARNING_500 + ">⚠ Bạn không đủ tiền để thực hiện giao dịch này.</color>"));
			scheduleAlertReset(player, view, slot, restoreItem);
			return;
		}

		int available = service.countSimilar(player.getInventory(), shopItem.itemStack());
		view.setItem(slot, item(Material.ORANGE_STAINED_GLASS_PANE, "<color:" + LunaPalette.WARNING_500 + ">⚠ Không đủ vật phẩm</color>", List.of(
			line(LunaPalette.WARNING_500, "♦ Cần: <white>" + amount),
			line(LunaPalette.WARNING_500, "♦ Hiện có: <white>" + available)
		)));
		player.sendMessage(mm("<color:" + LunaPalette.WARNING_500 + ">⚠ Bạn không đủ vật phẩm tương ứng để bán.</color>"));
		scheduleAlertReset(player, view, slot, restoreItem);
	}

	private void scheduleAlertReset(Player player, GuiView view, int slot, ItemStack restoreItem) {
		ItemStack restoreCopy = restoreItem.clone();
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			if (!player.isOnline()) {
				return;
			}

			if (!player.getOpenInventory().getTopInventory().equals(view.getInventory())) {
				return;
			}

			ItemStack current = view.getInventory().getItem(slot);
			if (current == null || current.getType() != Material.ORANGE_STAINED_GLASS_PANE) {
				return;
			}

			view.setItem(slot, restoreCopy);
		}, ALERT_RESET_TICKS);
	}

	private void fillFooter(GuiView view) {
		ItemStack footer = item(Material.GRAY_STAINED_GLASS_PANE, "<gray> ", List.of());
		for (int slot = 45; slot <= 53; slot++) {
			view.setItem(slot, footer);
		}
	}

	private ItemStack nav(Material material, String name) {
		return item(material, name, List.of());
	}

	private void openConfirmationDialog(Player player, String title, List<String> details, Runnable onConfirm, Runnable onCancel) {
		GuiView view = new GuiView(27, mm(title));
		guiManager.track(view);
		UUID playerId = player.getUniqueId();
		UUID token = UUID.randomUUID();
		pendingConfirmations.put(playerId, token);

		ItemStack spacer = item(Material.GRAY_STAINED_GLASS_PANE, "<gray> ", List.of());
		for (int slot = 0; slot < 27; slot++) {
			view.setItem(slot, spacer);
		}

		view.setItem(13, item(Material.PAPER, "<yellow>Chi tiết xác nhận", details));
		view.setItem(11, item(Material.LIME_CONCRETE, "<green>✔ Xác nhận", List.of("<gray>Thực hiện hành động này.")), (clicker, event, gui) -> {
			UUID current = pendingConfirmations.get(clicker.getUniqueId());
			if (current != null && current.equals(token)) {
				pendingConfirmations.remove(clicker.getUniqueId());
				onConfirm.run();
			}
		});
		view.setItem(15, item(Material.RED_CONCRETE, "<red>❌ Hủy", List.of("<gray>Quay lại màn trước.")), (clicker, event, gui) -> {
			UUID current = pendingConfirmations.get(clicker.getUniqueId());
			if (current != null && current.equals(token)) {
				pendingConfirmations.remove(clicker.getUniqueId());
				onCancel.run();
			}
		});
		player.openInventory(view.getInventory());

		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			UUID current = pendingConfirmations.get(playerId);
			if (current == null || !current.equals(token)) {
				return;
			}

			if (player.getOpenInventory().getTopInventory().equals(view.getInventory())) {
				pendingConfirmations.remove(playerId);
				player.sendMessage(mm("<yellow>⌛ Hết thời gian xác nhận, thao tác đã được hủy.</yellow>"));
				onCancel.run();
			}
		}, CONFIRMATION_TIMEOUT_TICKS);
	}

	private ItemStack item(Material material, String title, List<String> loreLines) {
		ArrayList<Component> lore = new ArrayList<>();
		for (String line : loreLines) {
			for (String wrapped : LunaLore.wrapLoreLine(line)) {
				lore.add(wrapped.isEmpty() ? Component.empty() : normalizeLoreComponent(mm(wrapped)));
			}
		}
		return LunaUi.item(material, title, lore);
	}

	private String line(String color, String text) {
		return "<color:" + color + ">▍ </color>" + text;
	}

	private String plainLine(String color, String text) {
		return "<color:" + color + ">" + text + "</color>";
	}

	private List<Component> appendShopLore(ItemMeta meta, List<String> shopLoreLines) {
		ArrayList<Component> mergedLore = new ArrayList<>();
		List<Component> originalLore = meta.lore();
		boolean hasOriginalLore = meta.hasLore() && originalLore != null && !originalLore.isEmpty();
		if (hasOriginalLore) {
			for (Component originalLine : originalLore) {
				mergedLore.add(normalizeLoreComponent(originalLine));
			}
		}

		mergedLore.add(Component.empty());

		for (String line : shopLoreLines) {
			for (String wrapped : LunaLore.wrapLoreLine(line)) {
				mergedLore.add(wrapped.isEmpty() ? Component.empty() : normalizeLoreComponent(mm(wrapped)));
			}
		}

		return mergedLore;
	}

	private Component normalizeLoreComponent(Component component) {
		return component
			.colorIfAbsent(NamedTextColor.WHITE)
			.decoration(TextDecoration.ITALIC, false);
	}

	private Component mm(String text) {
		return LunaUi.mini(text);
	}

	private int maxPage(int items) {
		return LunaPagination.maxPage(items, PAGE_SIZE);
	}

	private int clampPage(int page, int maxPage) {
		return LunaPagination.clampPage(page, maxPage);
	}

	private int clampAmount(int amount) {
		return Math.max(1, Math.min(4096, amount));
	}

	private String prettyCategory(String category) {
		String[] parts = category.split("-");
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (part.isBlank()) {
				continue;
			}

			if (!builder.isEmpty()) {
				builder.append(" ");
			}
			builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
		}

		return builder.isEmpty() ? "General" : builder.toString();
	}

	private String displayCategory(String categoryId) {
		return store.findCategory(categoryId)
			.filter(ShopCategory::hasDisplayName)
			.map(ShopCategory::displayName)
			.orElse("<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">" + prettyCategory(categoryId) + "</color>");
	}

	private enum TradeMode {
		BUY,
		SELL
	}

	private record BrowseContext(String category, String query, int page, boolean search) {
		static BrowseContext category(String category, int page) {
			return new BrowseContext(category, null, page, false);
		}

		static BrowseContext search(String query, int page) {
			return new BrowseContext(null, query, page, true);
		}

		static BrowseContext categorySearch(String category, String query, int page) {
			return new BrowseContext(category, query, page, true);
		}

		BrowseContext withPage(int value) {
			return new BrowseContext(category, query, value, search);
		}
	}

	private record SearchRequest(String category) {
	}

	private record TradeSession(String itemId, TradeMode mode, int amount, BrowseContext context) {
		TradeSession withAmount(int value) {
			return new TradeSession(itemId, mode, value, context);
		}

		TradeSession withMode(TradeMode value) {
			return new TradeSession(itemId, value, amount, context);
		}
	}

	private enum AdminPromptType {
		CREATE_CATEGORY,
		RENAME_CATEGORY,
		CREATE_ITEM,
		EDIT_ITEM_PRICES,
		MOVE_ITEM_CATEGORY
	}

	private record AdminPrompt(AdminPromptType type, String primary, String secondary, int page) {
	}

}

