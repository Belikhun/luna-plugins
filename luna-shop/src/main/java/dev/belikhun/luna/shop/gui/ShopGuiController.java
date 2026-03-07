package dev.belikhun.luna.shop.gui;

import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.gui.LunaPagination;
import dev.belikhun.luna.core.api.gui.NumberSelectorGui;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaLore;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.shop.model.ShopCategory;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.service.ShopTransactionEntry;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShopGuiController implements Listener {
	private static final int PAGE_SIZE = 45;
	private static final int HISTORY_PAGE_SIZE = 45;
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
	private final NumberSelectorGui numberSelector;
	private final PlainTextComponentSerializer plainText;
	private final Map<UUID, SearchRequest> waitingSearch;
	private final Map<UUID, ItemEditorTextPrompt> waitingItemEditorText;
	private final Map<UUID, CreateItemDraft> waitingCreateItemCategory;
	private final Map<UUID, AdminPrompt> waitingAdminPrompt;
	private final Map<UUID, UUID> pendingConfirmations;
	private final Map<UUID, ItemEditorSession> openItemEditors;

	public ShopGuiController(JavaPlugin plugin, ShopService service, ShopItemStore store) {
		this.plugin = plugin;
		this.service = service;
		this.store = store;
		this.guiManager = new GuiManager();
		this.numberSelector = new NumberSelectorGui(plugin, this.guiManager);
		this.plainText = PlainTextComponentSerializer.plainText();
		this.waitingSearch = new HashMap<>();
		this.waitingItemEditorText = new HashMap<>();
		this.waitingCreateItemCategory = new HashMap<>();
		this.waitingAdminPrompt = new HashMap<>();
		this.pendingConfirmations = new HashMap<>();
		this.openItemEditors = new HashMap<>();

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
				actionLine("Chuột trái", "mở")
			)
		), (clicker, event, gui) -> openCategoryManagement(clicker, 0));

		view.setItem(24, item(
			Material.BOOK,
			"<aqua>♦ Quản Lý Mặt Hàng",
			List.of(
				"<gray>● Quản lý item shop, giá và danh mục.",
				"",
				actionLine("Chuột trái", "mở")
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
				actionLine("Chuột trái", "đổi icon từ item tay"),
				actionLine("Chuột phải", "đổi tên danh mục"),
				actionLine("Shift+Chuột phải", "xóa danh mục rỗng")
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
			ArrayList<String> loreLines = new ArrayList<>(List.of(
				"<gray>№ <white>" + item.id(),
				"<gray>♦ Danh mục: " + displayCategory(item.category()),
				"<green>💰 Giá mua: <gold>" + service.formatMoney(item.buyPrice()),
				"<yellow>💵 Giá bán: <gold>" + service.formatMoney(item.sellPrice())
			));
			loreLines.addAll(adminTradeLimitLore(item));
			loreLines.addAll(List.of(
				"",
				actionLine("Chuột trái", "mở trình chỉnh sửa")
			));
			meta.lore(appendShopLore(meta, loreLines));
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
			icon.setItemMeta(meta);

			view.setItem(slot, icon, (clicker, event, gui) -> openItemEditor(clicker, item.id(), currentPage));
		}

		fillFooter(view);
		if (currentPage > 0) {
			view.setItem(45, nav(Material.ARROW, "<yellow>← Trang trước"), (clicker, event, gui) -> openItemManagement(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			view.setItem(53, nav(Material.ARROW, "<yellow>Trang sau →"), (clicker, event, gui) -> openItemManagement(clicker, currentPage + 1));
		}

		view.setItem(49, nav(Material.ANVIL, "<aqua>➕ Tạo item mới"), (clicker, event, gui) -> beginCreateItemFlow(clicker, currentPage));
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
				actionLine("Chuột trái", "mở danh mục này")
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
		view.setItem(50, nav(Material.BOOK, "<yellow>⌚ Lịch sử giao dịch"), (clicker, event, gui) -> openTransactionHistory(clicker, 0));
		view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> clicker.closeInventory());

		player.openInventory(view.getInventory());
	}

	private void openItemEditor(Player player, String itemId, int page) {
		ShopItem item = store.find(itemId).orElse(null);
		if (item == null) {
			player.sendMessage(mm("<red>❌ Item không tồn tại.</red>"));
			openItemManagement(player, page);
			return;
		}

		openItemEditors.put(player.getUniqueId(), new ItemEditorSession(item.id(), page));

		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Sửa Mặt Hàng"));
		guiManager.track(view);
		fillFooter(view);

		ItemStack preview = item.itemStack().clone();
		ItemMeta previewMeta = preview.getItemMeta();
		ArrayList<String> previewLore = new ArrayList<>(List.of(
			"<gray>№ <white>" + item.id(),
			"<gray>♦ Danh mục: " + displayCategory(item.category()),
			"<green>💰 Giá mua: <gold>" + service.formatMoney(item.buyPrice()),
			"<yellow>💵 Giá bán: <gold>" + service.formatMoney(item.sellPrice())
		));
		previewLore.addAll(adminTradeLimitLore(item));
		previewMeta.lore(appendShopLore(previewMeta, previewLore));
		previewMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		preview.setItemMeta(previewMeta);
		view.setItem(13, preview);

		view.setItem(28, item(Material.NAME_TAG, "<aqua>✎ Sửa ID", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <white>" + item.id() + "</white>"),
			actionLine("Chuột trái", "nhập ID mới bằng chat")
		)), (clicker, event, gui) -> beginItemEditorTextPrompt(clicker, item.id(), page, ItemEditField.ID, item.id()));

		view.setItem(29, item(Material.EMERALD, "<green>💰 Sửa giá mua", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <gold>" + service.formatMoney(item.buyPrice()) + "</gold>"),
			actionLine("Chuột trái", "mở bộ chọn số")
		)), (clicker, event, gui) -> beginItemEditorNumberSelector(clicker, item.id(), page, ItemEditField.BUY_PRICE, item.buyPrice()));

		view.setItem(30, item(Material.GOLD_INGOT, "<yellow>💵 Sửa giá bán", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <gold>" + service.formatMoney(item.sellPrice()) + "</gold>"),
			actionLine("Chuột trái", "mở bộ chọn số")
		)), (clicker, event, gui) -> beginItemEditorNumberSelector(clicker, item.id(), page, ItemEditField.SELL_PRICE, item.sellPrice()));

		view.setItem(31, item(Material.CHEST, "<aqua>♦ Sửa danh mục", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: " + displayCategory(item.category())),
			actionLine("Chuột trái", "nhập category bằng chat")
		)), (clicker, event, gui) -> beginItemEditorTextPrompt(clicker, item.id(), page, ItemEditField.CATEGORY, item.category()));

		view.setItem(32, item(Material.LIME_DYE, "<green>⌚ Hạn mức mua", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <white>" + limitSettingText(item.buyTradeLimit()) + "</white>"),
			plainLine(LunaPalette.NEUTRAL_100, "Nhập <white>0</white> để bỏ giới hạn"),
			actionLine("Chuột trái", "mở bộ chọn số")
		)), (clicker, event, gui) -> beginItemEditorNumberSelector(clicker, item.id(), page, ItemEditField.BUY_LIMIT, item.buyTradeLimit()));

		view.setItem(33, item(Material.ORANGE_DYE, "<yellow>⌚ Hạn mức bán", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <white>" + limitSettingText(item.sellTradeLimit()) + "</white>"),
			plainLine(LunaPalette.NEUTRAL_100, "Nhập <white>0</white> để bỏ giới hạn"),
			actionLine("Chuột trái", "mở bộ chọn số")
		)), (clicker, event, gui) -> beginItemEditorNumberSelector(clicker, item.id(), page, ItemEditField.SELL_LIMIT, item.sellTradeLimit()));

		view.setItem(34, item(Material.ANVIL, "<aqua>🔧 Cập nhật metadata", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Lấy item đang cầm làm metadata mới"),
			actionLine("Chuột trái", "cập nhật ngay")
		)), (clicker, event, gui) -> {
			ItemStack hand = clicker.getInventory().getItemInMainHand();
			if (hand.getType().isAir()) {
				clicker.sendMessage(mm("<red>❌ Hãy cầm item trên tay để cập nhật metadata.</red>"));
				openItemEditor(clicker, item.id(), page);
				return;
			}

			ShopItem updated = ShopItem.fromItemStack(item.id(), item.category(), item.buyPrice(), item.sellPrice(), item.buyTradeLimit(), item.sellTradeLimit(), hand, item.addedDate());
			store.upsert(updated);
			clicker.sendMessage(mm("<green>✔ Đã cập nhật metadata cho item <white>" + item.id() + "</white>.</green>"));
			openItemEditor(clicker, item.id(), page);
		});

		view.setItem(39, item(Material.BARRIER, "<red>❌ Xóa mặt hàng", List.of(
			plainLine(LunaPalette.DANGER_500, "Hành động không thể hoàn tác"),
			actionLine("Chuột trái", "xác nhận xóa")
		)), (clicker, event, gui) -> openConfirmationDialog(
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
				openItemEditors.remove(clicker.getUniqueId());
				clicker.sendMessage(mm("<green>✔ Đã xóa item <white>" + item.id() + "</white> khỏi shop.</green>"));
				openItemManagement(clicker, page);
			},
			() -> openItemEditor(clicker, item.id(), page)
		));

		view.setItem(49, nav(Material.ARROW, "<yellow>← Quay lại danh sách"), (clicker, event, gui) -> {
			openItemEditors.remove(clicker.getUniqueId());
			openItemManagement(clicker, page);
		});
		view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> {
			openItemEditors.remove(clicker.getUniqueId());
			clicker.closeInventory();
		});

		player.openInventory(view.getInventory());
	}

	private void beginItemEditorTextPrompt(Player player, String itemId, int page, ItemEditField field, String initialValue) {
		openItemEditors.put(player.getUniqueId(), new ItemEditorSession(itemId, page));
		String label = switch (field) {
			case ID -> "<aqua>Nhập ID mới";
			case CATEGORY -> "<aqua>Nhập category";
			default -> "<aqua>Nhập giá trị";
		};

		waitingItemEditorText.put(player.getUniqueId(), new ItemEditorTextPrompt(itemId, page, field));
		player.closeInventory();
		player.sendMessage(mm(label + " <gray>(hiện tại: <white>" + (initialValue == null ? "" : initialValue) + "</white>)</gray>"));
		player.sendMessage(mm("<aqua>✦ Nhập trên chat. Gõ <white>huy</white> để hủy.</aqua>"));
	}

	private void beginItemEditorNumberSelector(Player player, String itemId, int page, ItemEditField field, double initialValue) {
		openItemEditors.put(player.getUniqueId(), new ItemEditorSession(itemId, page));
		String title = switch (field) {
			case BUY_PRICE -> "<green>Nhập giá mua";
			case SELL_PRICE -> "<yellow>Nhập giá bán";
			case BUY_LIMIT -> "<color:#374151>Nhập hạn mức mua";
			case SELL_LIMIT -> "<color:#374151>Nhập hạn mức bán";
			default -> "<aqua>Nhập số";
		};

		boolean integerMode = field == ItemEditField.BUY_LIMIT || field == ItemEditField.SELL_LIMIT;
		double maxValue = integerMode ? 1000000D : 1000000000D;
		NumberSelectorGui.Request request = NumberSelectorGui
			.request(mm(title), "Giá trị", (submitPlayer, value) -> {
				double normalized = integerMode ? Math.max(0D, Math.rint(value)) : Math.max(0D, value);
				String serialized = integerMode
					? String.valueOf((int) Math.rint(normalized))
					: String.valueOf(normalized);
				applyItemEditorInput(submitPlayer, itemId, page, field, serialized);
			}, closePlayer -> openItemEditor(closePlayer, itemId, page))
			.withDisplayMaterial(Material.PAPER)
			.withInitialValue(initialValue)
			.withMinValue(0D)
			.withMaxValue(maxValue)
			.withIntegerMode(integerMode)
			.withNumberDisplayFormatter(value -> {
				if (integerMode) {
					return String.valueOf((int) Math.rint(value));
				}
				return service.formatMoney(value);
			})
			.withUnit(integerMode ? "lượt" : "");

		numberSelector.open(player, request);
	}

	public void openCategoryMenu(Player player, String category, int page) {
		List<ShopItem> items = store.byCategory(category);

		BrowseContext context = BrowseContext.category(category, page);
		openItemList(player, items, LunaUi.guiTitleBreadcrumb("Luna Shop", "Danh Mục", prettyCategory(category)), context);
	}

	public void openSearchMenu(Player player, String query, int page) {
		List<ShopItem> items = store.search(query);

		BrowseContext context = BrowseContext.search(query, page);
		openItemList(player, items, LunaUi.guiTitleBreadcrumb("Luna Shop", "Tìm Kiếm", query), context);
	}

	private void openItemList(Player player, List<ShopItem> items, Component title, BrowseContext context) {
		GuiView view = new GuiView(54, title);
		guiManager.track(view);

		List<ShopItem> sortedItems = sortItems(items, context.sortField(), context.sortAscending());

		int maxPage = maxPage(sortedItems.size());
		int currentPage = clampPage(context.page(), maxPage);
		int start = currentPage * PAGE_SIZE;
		int end = Math.min(sortedItems.size(), start + PAGE_SIZE);
		for (int i = start; i < end; i++) {
			ShopItem shopItem = sortedItems.get(i);
			int slot = i - start;
			ItemStack icon = shopItem.itemStack().clone();
			ItemMeta meta = icon.getItemMeta();
			ArrayList<String> loreLines = new ArrayList<>(List.of(
				"<gray>№ <white>" + shopItem.id(),
				"<gray>♦ Danh mục: " + displayCategory(shopItem.category()),
				"<green>💰 Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()),
				"<yellow>💵 Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice()),
				""
			));
			loreLines.addAll(playerTradeLimitLore(player, shopItem));
			loreLines.addAll(List.of(
				"",
				actionLine("Chuột trái", "mua"),
				actionLine("Chuột phải", "bán")
			));
			meta.lore(appendShopLore(meta, loreLines));
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

		view.setItem(47, item(Material.HOPPER, "<aqua>⚙ Sắp xếp theo", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Tiêu chí hiện tại: <yellow>" + sortLabel(context.sortField()) + "</yellow>"),
			actionLine("Chuột trái", "đổi tiêu chí")
		)), (clicker, event, gui) -> openItemList(clicker, items, title, context.withSortField(nextSortField(context.sortField()))));

		view.setItem(48, item(Material.COMPARATOR, "<aqua>⇅ Thứ tự", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <yellow>" + (context.sortAscending() ? "Tăng dần" : "Giảm dần") + "</yellow>"),
			actionLine("Chuột trái", "đảo thứ tự")
		)), (clicker, event, gui) -> openItemList(clicker, items, title, context.toggleSortDirection()));

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

	public void openTransactionHistory(Player player, int page) {
		openTransactionHistory(player, player.getUniqueId(), player.getName(), page, false);
	}

	public void openTransactionHistoryAdmin(Player admin, UUID targetUuid, String targetName, int page) {
		openTransactionHistory(admin, targetUuid, targetName, page, true);
	}

	private void openTransactionHistory(Player viewer, UUID targetUuid, String targetName, int page, boolean adminView) {
		String normalizedTargetName = (targetName == null || targetName.isBlank()) ? "Unknown" : targetName;
		GuiView view = new GuiView(54, LunaUi.guiTitleBreadcrumb("Luna Shop", "Lịch Sử"));
		guiManager.track(view);

		if (!service.isTransactionHistoryEnabled()) {
			fillFooter(view);
			view.setItem(22, item(Material.BARRIER, "<red>❌ Database chưa bật", List.of(
				plainLine(LunaPalette.WARNING_500, "Lịch sử giao dịch yêu cầu database"),
				plainLine(LunaPalette.NEUTRAL_100, "Hãy bật LunaCore database API")
			)));
			view.setItem(49, nav(Material.CHEST, adminView ? "<yellow>Quay lại quản lý" : "<yellow>Danh mục chính"), (clicker, event, gui) -> {
				if (adminView) {
					openManagementMenu(clicker, 0);
					return;
				}

				openMainMenu(clicker, 0);
			});
			view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> clicker.closeInventory());
			viewer.openInventory(view.getInventory());
			return;
		}

		int total = service.transactionHistoryCount(targetUuid);
		int maxPage = LunaPagination.maxPage(total, HISTORY_PAGE_SIZE);
		int currentPage = LunaPagination.clampPage(page, maxPage);
		List<ShopTransactionEntry> entries = service.transactionHistory(targetUuid, currentPage, HISTORY_PAGE_SIZE);

		for (int i = 0; i < entries.size() && i < HISTORY_PAGE_SIZE; i++) {
			ShopTransactionEntry entry = entries.get(i);
			Material material = entry.success() ? Material.LIME_DYE : Material.RED_DYE;
			String actionText = entry.action().equalsIgnoreCase("BUY") ? "MUA" : "BÁN";
			String actionColor = entry.action().equalsIgnoreCase("BUY") ? LunaPalette.SUCCESS_500 : LunaPalette.WARNING_500;
			String resultColor = entry.success() ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500;
			String statusText = entry.success() ? "THÀNH CÔNG" : "THẤT BẠI";

			view.setItem(i, item(material, "<color:" + actionColor + ">" + actionText + "</color> <white>#" + entry.transactionId().substring(0, Math.min(8, entry.transactionId().length())) + "</white>", List.of(
				"<white>№ Item: <yellow>" + entry.itemId(),
				"<white>♦ Danh mục: " + displayCategory(entry.category()),
				"<white>♦ Số lượng: <yellow>" + entry.amount(),
				"<white>♦ Đơn giá: <gold>" + service.formatMoney(entry.unitPrice()),
				"<white>♦ Tổng tiền: <gold>" + service.formatMoney(entry.totalPrice()),
				"<white>♦ Kết quả: <color:" + resultColor + ">" + statusText + "</color>",
				"<white>⌚ Thời gian: <gray>" + Formatters.date(Instant.ofEpochMilli(entry.createdAt())),
				entry.success() ? "" : "<white>⚠ Lý do: <color:" + LunaPalette.DANGER_500 + ">" + entry.reason() + "</color>"
			)));
		}

		fillFooter(view);
		if (currentPage > 0) {
			view.setItem(45, nav(Material.ARROW, "<yellow>← Trang trước"), (clicker, event, gui) -> openTransactionHistory(clicker, targetUuid, normalizedTargetName, currentPage - 1, adminView));
		}
		if (currentPage < maxPage) {
			view.setItem(53, nav(Material.ARROW, "<yellow>Trang sau →"), (clicker, event, gui) -> openTransactionHistory(clicker, targetUuid, normalizedTargetName, currentPage + 1, adminView));
		}

		view.setItem(49, nav(Material.CHEST, adminView ? "<yellow>Quay lại quản lý" : "<yellow>Danh mục chính"), (clicker, event, gui) -> {
			if (adminView) {
				openManagementMenu(clicker, 0);
				return;
			}

			openMainMenu(clicker, 0);
		});
		view.setItem(50, item(Material.PAPER, "<aqua>ℹ Thông tin", List.of(
			plainLine(LunaPalette.NEUTRAL_100, "Người chơi: <yellow>" + normalizedTargetName + "</yellow>"),
			plainLine(LunaPalette.NEUTRAL_100, "Tổng giao dịch: <yellow>" + total + "</yellow>"),
			plainLine(LunaPalette.NEUTRAL_100, "Trang: <yellow>" + (currentPage + 1) + "/" + (maxPage + 1) + "</yellow>")
		)));
		view.setItem(52, nav(Material.OAK_DOOR, "<red>Đóng"), (clicker, event, gui) -> clicker.closeInventory());

		viewer.openInventory(view.getInventory());
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
		GuiView view = new GuiView(54, tradeTitleWithAmount(amount));
		guiManager.track(view);

		fillFooter(view);

		ItemStack display = shopItem.itemStack().clone();
		ItemMeta meta = display.getItemMeta();
		int displayStackCap = Math.max(1, Math.min(99, amount));
		meta.setMaxStackSize(displayStackCap);
		int cappedBuyAmount = service.capBuyAmount(player, shopItem, amount);
		int cappedSellAmount = service.capSellAmount(player, shopItem, amount);
		double buyTotal = shopItem.buyPrice() * cappedBuyAmount;
		double sellTotal = shopItem.sellPrice() * cappedSellAmount;
		ArrayList<String> displayLore = new ArrayList<>(List.of(
			"<white>№ <yellow>" + shopItem.id(),
			"<white>♦ Danh mục: " + displayCategory(shopItem.category()),
			"<white>♦ Số lượng: <yellow>" + amount,
			"<green>💰 Tổng mua (áp dụng): <gold>" + service.formatMoney(buyTotal),
			"<yellow>💵 Tổng bán (áp dụng): <gold>" + service.formatMoney(sellTotal),
			"",
			"<white>💰 Số dư hiện tại: <yellow>" + service.formatMoney(service.economy().balance(player))
		));
		displayLore.add("");
		displayLore.addAll(playerTradeLimitLore(player, shopItem));
		meta.lore(appendShopLore(meta, displayLore));
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		display.setItemMeta(meta);
		display.setAmount(Math.min(displayStackCap, amount));
		view.setItem(13, display);

		view.setItem(20, modeButton(TradeMode.BUY, normalized.mode() == TradeMode.BUY), (clicker, event, gui) -> openTradeMenu(clicker, normalized.withMode(TradeMode.BUY)));
		view.setItem(24, modeButton(TradeMode.SELL, normalized.mode() == TradeMode.SELL), (clicker, event, gui) -> openTradeMenu(clicker, normalized.withMode(TradeMode.SELL)));

		for (int i = 0; i < QUICK_AMOUNTS.length; i++) {
			int amountValue = QUICK_AMOUNTS[i];
			int quickSlot = QUICK_AMOUNT_SLOTS[i];
			ItemStack quickButtonItem = amountButton(shopItem, normalized.mode(), amountValue);
			view.setItem(quickSlot, quickButtonItem, (clicker, event, gui) -> {
				TradeSession quickSession = normalized.withAmount(amountValue);
				int effectiveAmount = effectiveTradeAmount(clicker, shopItem, quickSession.mode(), quickSession.amount());
				if (effectiveAmount <= 0) {
					showLimitReachedAlert(clicker, gui, quickSlot, quickSession.mode(), shopItem, quickButtonItem);
					return;
				}

				if (quickSession.mode() == TradeMode.BUY && !hasEnoughMoney(clicker, shopItem, effectiveAmount)) {
					showInsufficientAlert(clicker, gui, quickSlot, quickSession.mode(), shopItem, quickSession.amount(), quickButtonItem);
					return;
				}

				if (quickSession.mode() == TradeMode.SELL && !hasEnoughItems(clicker, shopItem, effectiveAmount)) {
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
			line(LunaPalette.INFO_500, "ℹ Mở bộ chọn số lượng"),
			"",
			line(LunaPalette.NEUTRAL_100, "Bước chỉnh: <white>1..512</white>"),
			plainLine(LunaPalette.NEUTRAL_100, "Có thể nhập trực tiếp qua chat")
		)), (clicker, event, gui) -> beginTradeAmountSelector(clicker, normalized));
		view.setItem(45, item(Material.ARROW, "<yellow>← Quay lại", List.of(
			plainLine(LunaPalette.WARNING_500, "Quay về danh sách trước"),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Số lượng vừa chọn vẫn giữ")
		)), (clicker, event, gui) -> returnToBrowse(clicker, normalized.context()));

		if (normalized.mode() == TradeMode.SELL) {
			int quickSellAllAmount = service.countSimilar(player.getInventory(), shopItem.itemStack());
			ItemStack quickSellAllItem = quickSellAllButton(player, shopItem, quickSellAllAmount);
			view.setItem(23, quickSellAllItem, (clicker, event, gui) -> {
				int sellAmount = service.countSimilar(clicker.getInventory(), shopItem.itemStack());
				int effectiveSellAmount = service.capSellAmount(clicker, shopItem, sellAmount);
				if (sellAmount <= 0) {
					clicker.sendMessage(mm("<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>"));
					showInsufficientAlert(clicker, gui, 23, TradeMode.SELL, shopItem, 1, quickSellAllItem);
					return;
				}

				if (effectiveSellAmount <= 0) {
					showLimitReachedAlert(clicker, gui, 23, TradeMode.SELL, shopItem, quickSellAllItem);
					return;
				}

				double expected = shopItem.sellPrice() * effectiveSellAmount;
				openConfirmationDialog(
					clicker,
					"<yellow>⚠ Xác nhận bán nhanh toàn bộ",
					List.of(
						"<gray>Vật phẩm: <white>" + shopItem.id(),
						"<gray>Số lượng sẽ bán: <white>" + effectiveSellAmount,
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
		int effectiveAmount = effectiveTradeAmount(player, shopItem, session.mode(), session.amount());
		if (effectiveAmount <= 0) {
			showLimitReachedAlert(player, view, clickedSlot, session.mode(), shopItem, restoreItem);
			return;
		}

		if (session.mode() == TradeMode.BUY && !hasEnoughMoney(player, shopItem, effectiveAmount)) {
			showInsufficientAlert(player, view, clickedSlot, session.mode(), shopItem, session.amount(), restoreItem);
			return;
		}

		if (session.mode() == TradeMode.SELL && !hasEnoughItems(player, shopItem, effectiveAmount)) {
			showInsufficientAlert(player, view, clickedSlot, session.mode(), shopItem, session.amount(), restoreItem);
			return;
		}

		if (session.mode() == TradeMode.BUY) {
			double total = shopItem.buyPrice() * effectiveAmount;
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

		if (waitingItemEditorText.containsKey(uuid)) {
			event.setCancelled(true);
			ItemEditorTextPrompt prompt = waitingItemEditorText.remove(uuid);
			String input = plainText.serialize(event.message()).trim();
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				if (input.isBlank() || input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel")) {
					player.sendMessage(mm("<yellow>ℹ Đã hủy chỉnh sửa.</yellow>"));
					openItemEditor(player, prompt.itemId(), prompt.page());
					return;
				}

				applyItemEditorInput(player, prompt.itemId(), prompt.page(), prompt.field(), input);
			});
			return;
		}

		if (waitingCreateItemCategory.containsKey(uuid)) {
			event.setCancelled(true);
			CreateItemDraft draft = waitingCreateItemCategory.remove(uuid);
			String input = plainText.serialize(event.message()).trim();
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				if (input.isBlank() || input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel")) {
					player.sendMessage(mm("<yellow>ℹ Đã hủy tạo item.</yellow>"));
					openItemManagement(player, draft.page());
					return;
				}

				String category = ShopItem.normalizeCategory(input);
				if (store.findCategory(category).isEmpty()) {
					player.sendMessage(mm("<red>❌ Category không tồn tại. Hãy nhập lại hoặc gõ <white>huy</white>.</red>"));
					waitingCreateItemCategory.put(player.getUniqueId(), draft);
					return;
				}

				openCreateItemBuyPriceSelector(player, draft.withCategory(category));
			});
			return;
		}

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

		if (waitingAdminPrompt.containsKey(uuid)) {
			event.setCancelled(true);
			AdminPrompt prompt = waitingAdminPrompt.remove(uuid);
			String input = plainText.serialize(event.message()).trim();
			plugin.getServer().getScheduler().runTask(plugin, () -> handleAdminPrompt(player, prompt, input));
			return;
		}
	}

	private void applyItemEditorInput(Player player, String itemId, int page, ItemEditField field, String input) {
		ShopItem item = store.find(itemId).orElse(null);
		if (item == null) {
			player.sendMessage(mm("<red>❌ Item không tồn tại.</red>"));
			openItemManagement(player, page);
			return;
		}

		try {
			ShopItem updated = switch (field) {
				case ID -> applyItemId(item, input);
				case BUY_PRICE -> applyBuyPrice(item, input);
				case SELL_PRICE -> applySellPrice(item, input);
				case CATEGORY -> applyCategory(item, input);
				case BUY_LIMIT -> applyBuyLimit(item, input);
				case SELL_LIMIT -> applySellLimit(item, input);
			};

			if (!updated.id().equals(item.id())) {
				store.remove(item.id());
			}

			store.upsert(updated);
			player.sendMessage(mm("<green>✔ Đã cập nhật <white>" + updated.id() + "</white>.</green>"));
			openItemEditor(player, updated.id(), page);
		} catch (IllegalArgumentException exception) {
			player.sendMessage(mm("<red>❌ " + exception.getMessage() + "</red>"));
			openItemEditor(player, item.id(), page);
		}
	}

	private ShopItem applyItemId(ShopItem item, String rawValue) {
		String normalized = ShopItem.normalizeId(rawValue);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("ID không hợp lệ.");
		}

		ShopItem duplicate = store.find(normalized).orElse(null);
		if (duplicate != null && !duplicate.id().equals(item.id())) {
			throw new IllegalArgumentException("ID này đã tồn tại trong shop.");
		}

		return new ShopItem(normalized, item.category(), item.buyPrice(), item.sellPrice(), item.buyTradeLimit(), item.sellTradeLimit(), item.itemData(), item.addedDate());
	}

	private ShopItem applyBuyPrice(ShopItem item, String rawValue) {
		double value = parseNonNegativeDouble(rawValue, "Giá mua phải là số >= 0.");
		return new ShopItem(item.id(), item.category(), value, item.sellPrice(), item.buyTradeLimit(), item.sellTradeLimit(), item.itemData(), item.addedDate());
	}

	private ShopItem applySellPrice(ShopItem item, String rawValue) {
		double value = parseNonNegativeDouble(rawValue, "Giá bán phải là số >= 0.");
		return new ShopItem(item.id(), item.category(), item.buyPrice(), value, item.buyTradeLimit(), item.sellTradeLimit(), item.itemData(), item.addedDate());
	}

	private ShopItem applyCategory(ShopItem item, String rawValue) {
		String categoryId = ShopItem.normalizeCategory(rawValue);
		if (store.findCategory(categoryId).isEmpty()) {
			throw new IllegalArgumentException("Category không tồn tại.");
		}

		return new ShopItem(item.id(), categoryId, item.buyPrice(), item.sellPrice(), item.buyTradeLimit(), item.sellTradeLimit(), item.itemData(), item.addedDate());
	}

	private ShopItem applyBuyLimit(ShopItem item, String rawValue) {
		int value = parseTradeLimitInput(rawValue);
		return new ShopItem(item.id(), item.category(), item.buyPrice(), item.sellPrice(), value, item.sellTradeLimit(), item.itemData(), item.addedDate());
	}

	private ShopItem applySellLimit(ShopItem item, String rawValue) {
		int value = parseTradeLimitInput(rawValue);
		return new ShopItem(item.id(), item.category(), item.buyPrice(), item.sellPrice(), item.buyTradeLimit(), value, item.itemData(), item.addedDate());
	}

	private double parseNonNegativeDouble(String raw, String errorMessage) {
		try {
			double value = Double.parseDouble(raw.trim());
			if (value < 0D) {
				throw new IllegalArgumentException(errorMessage);
			}
			return value;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(errorMessage);
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
				int buyTradeLimit = 0;
				int sellTradeLimit = 0;
				try {
					buyPrice = Double.parseDouble(parts[1]);
					sellPrice = Double.parseDouble(parts[2]);
					if (parts.length >= 5) {
						buyTradeLimit = parseTradeLimitInput(parts[3]);
						sellTradeLimit = parseTradeLimitInput(parts[4]);
					}
				} catch (NumberFormatException exception) {
					player.sendMessage(mm("<red>❌ Giá phải là số hợp lệ.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				} catch (IllegalArgumentException exception) {
					player.sendMessage(mm("<red>❌ Hạn mức phải là số nguyên >= 0 hoặc <white>none</white>.</red>"));
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

				ShopItem created = ShopItem.fromItemStackAutoId(category, buyPrice, sellPrice, buyTradeLimit, sellTradeLimit, hand);
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

				store.upsert(new ShopItem(item.id(), item.category(), buyPrice, sellPrice, item.buyTradeLimit(), item.sellTradeLimit(), item.itemData(), item.addedDate()));
				player.sendMessage(mm("<green>✔ Đã cập nhật giá cho item <white>" + item.id() + "</white>.</green>"));
				returnToAdminMenu(player, prompt);
			}
			case EDIT_ITEM_LIMITS -> {
				ShopItem item = store.find(prompt.primary()).orElse(null);
				if (item == null) {
					player.sendMessage(mm("<red>❌ Item không tồn tại.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				String[] parts = input.split("\\s+");
				if (parts.length < 2) {
					player.sendMessage(mm("<color:" + LunaPalette.WARNING_500 + ">ℹ Dùng:</color> " + CommandStrings.arguments(CommandStrings.required("buyLimit", "number|none"), CommandStrings.required("sellLimit", "number|none"))));
					returnToAdminMenu(player, prompt);
					return;
				}

				int buyLimit;
				int sellLimit;
				try {
					buyLimit = parseTradeLimitInput(parts[0]);
					sellLimit = parseTradeLimitInput(parts[1]);
				} catch (IllegalArgumentException exception) {
					player.sendMessage(mm("<red>❌ Hạn mức phải là số nguyên >= 0 hoặc <white>none</white>.</red>"));
					returnToAdminMenu(player, prompt);
					return;
				}

				store.upsert(new ShopItem(item.id(), item.category(), item.buyPrice(), item.sellPrice(), buyLimit, sellLimit, item.itemData(), item.addedDate()));
				player.sendMessage(mm("<green>✔ Đã cập nhật hạn mức cho item <white>" + item.id() + "</white>.</green>"));
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

				store.upsert(new ShopItem(item.id(), input, item.buyPrice(), item.sellPrice(), item.buyTradeLimit(), item.sellTradeLimit(), item.itemData(), item.addedDate()));
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
			case CREATE_ITEM -> player.sendMessage(mm("<aqua>✦ Nhập theo mẫu: </aqua>" + CommandStrings.syntax("/shopadmin", CommandStrings.literal("add"), CommandStrings.required("category", "text"), CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number"), CommandStrings.optional("buyLimit", "number|none"), CommandStrings.optional("sellLimit", "number|none")) + "<gray>. Gõ <white>huy</white> để hủy.</gray>"));
			case EDIT_ITEM_PRICES -> player.sendMessage(mm("<aqua>✦ Nhập giá mới cho <white>" + primary + "</white>: </aqua>" + CommandStrings.arguments(CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number")) + "<gray>. Gõ <white>huy</white> để hủy.</gray>"));
			case EDIT_ITEM_LIMITS -> player.sendMessage(mm("<aqua>✦ Nhập hạn mức cho <white>" + primary + "</white>: </aqua>" + CommandStrings.arguments(CommandStrings.required("buyLimit", "number|none"), CommandStrings.required("sellLimit", "number|none")) + "<gray>. Gõ <white>huy</white> để hủy.</gray>"));
			case MOVE_ITEM_CATEGORY -> player.sendMessage(mm("<aqua>✦ Nhập category mới cho <white>" + primary + "</white>. Gõ <white>huy</white> để hủy.</aqua>"));
		}
	}

	private void returnToAdminMenu(Player player, AdminPrompt prompt) {
		switch (prompt.type()) {
			case CREATE_CATEGORY, RENAME_CATEGORY -> openCategoryManagement(player, prompt.page());
			case CREATE_ITEM, EDIT_ITEM_PRICES, EDIT_ITEM_LIMITS, MOVE_ITEM_CATEGORY -> openItemManagement(player, prompt.page());
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

	private void beginTradeAmountSelector(Player player, TradeSession session) {
		NumberSelectorGui.Request request = NumberSelectorGui
			.request(
				LunaUi.guiTitleBreadcrumb("Luna Shop", "Giao Dịch", "Số Lượng"),
				"Số lượng giao dịch",
				(submitPlayer, value) -> {
					int amount = clampAmount((int) Math.rint(value));
					openTradeMenu(submitPlayer, session.withAmount(amount));
				},
				closePlayer -> openTradeMenu(closePlayer, session)
			)
			.withDisplayMaterial(Material.PAPER)
			.withInitialValue(session.amount())
			.withMinValue(1D)
			.withMaxValue(4096D)
			.withIntegerMode(true)
			.withNumberDisplayFormatter(value -> String.valueOf((int) Math.rint(value)))
			.withUnit("món");

		numberSelector.open(player, request);
	}

	private void beginCreateItemFlow(Player player, int page) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir()) {
			player.sendMessage(mm("<red>❌ Hãy cầm item trên tay để tạo mặt hàng.</red>"));
			openItemManagement(player, page);
			return;
		}

		waitingCreateItemCategory.put(player.getUniqueId(), CreateItemDraft.initial(page, hand.clone()));
		player.closeInventory();
		player.sendMessage(mm("<aqua>✦ Nhập <white>category</white> cho item mới. Gõ <white>huy</white> để hủy.</aqua>"));
	}

	private void openCreateItemBuyPriceSelector(Player player, CreateItemDraft draft) {
		NumberSelectorGui.Request request = NumberSelectorGui
			.request(
				LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Tạo Item", "Giá Mua"),
				"Giá mua",
				(submitPlayer, value) -> openCreateItemSellPriceSelector(submitPlayer, draft.withBuyPrice(Math.max(0D, value))),
				closePlayer -> {
					closePlayer.sendMessage(mm("<yellow>ℹ Đã hủy tạo item.</yellow>"));
					openItemManagement(closePlayer, draft.page());
				}
			)
			.withDisplayMaterial(Material.EMERALD)
			.withInitialValue(Math.max(0D, draft.buyPrice()))
			.withMinValue(0D)
			.withMaxValue(1000000000D)
			.withIntegerMode(false)
			.withNumberDisplayFormatter(service::formatMoney);

		numberSelector.open(player, request);
	}

	private void openCreateItemSellPriceSelector(Player player, CreateItemDraft draft) {
		NumberSelectorGui.Request request = NumberSelectorGui
			.request(
				LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Tạo Item", "Giá Bán"),
				"Giá bán",
				(submitPlayer, value) -> openCreateItemBuyLimitSelector(submitPlayer, draft.withSellPrice(Math.max(0D, value))),
				closePlayer -> {
					closePlayer.sendMessage(mm("<yellow>ℹ Đã hủy tạo item.</yellow>"));
					openItemManagement(closePlayer, draft.page());
				}
			)
			.withDisplayMaterial(Material.GOLD_INGOT)
			.withInitialValue(Math.max(0D, draft.sellPrice()))
			.withMinValue(0D)
			.withMaxValue(1000000000D)
			.withIntegerMode(false)
			.withNumberDisplayFormatter(service::formatMoney);

		numberSelector.open(player, request);
	}

	private void openCreateItemBuyLimitSelector(Player player, CreateItemDraft draft) {
		NumberSelectorGui.Request request = NumberSelectorGui
			.request(
				LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Tạo Item", "Hạn Mức Mua"),
				"Hạn mức mua",
				(submitPlayer, value) -> openCreateItemSellLimitSelector(submitPlayer, draft.withBuyLimit((int) Math.max(0D, Math.rint(value)))),
				closePlayer -> {
					closePlayer.sendMessage(mm("<yellow>ℹ Đã hủy tạo item.</yellow>"));
					openItemManagement(closePlayer, draft.page());
				}
			)
			.withDisplayMaterial(Material.LIME_DYE)
			.withInitialValue(Math.max(0, draft.buyLimit()))
			.withMinValue(0D)
			.withMaxValue(1000000D)
			.withIntegerMode(true)
			.withNumberDisplayFormatter(value -> String.valueOf((int) Math.rint(value)))
			.withUnit("lượt");

		numberSelector.open(player, request);
	}

	private void openCreateItemSellLimitSelector(Player player, CreateItemDraft draft) {
		NumberSelectorGui.Request request = NumberSelectorGui
			.request(
				LunaUi.guiTitleBreadcrumb("Luna Shop", "Quản Lý", "Tạo Item", "Hạn Mức Bán"),
				"Hạn mức bán",
				(submitPlayer, value) -> finishCreateItem(submitPlayer, draft.withSellLimit((int) Math.max(0D, Math.rint(value)))),
				closePlayer -> {
					closePlayer.sendMessage(mm("<yellow>ℹ Đã hủy tạo item.</yellow>"));
					openItemManagement(closePlayer, draft.page());
				}
			)
			.withDisplayMaterial(Material.ORANGE_DYE)
			.withInitialValue(Math.max(0, draft.sellLimit()))
			.withMinValue(0D)
			.withMaxValue(1000000D)
			.withIntegerMode(true)
			.withNumberDisplayFormatter(value -> String.valueOf((int) Math.rint(value)))
			.withUnit("lượt");

		numberSelector.open(player, request);
	}

	private void finishCreateItem(Player player, CreateItemDraft draft) {
		if (store.findCategory(draft.category()).isEmpty()) {
			player.sendMessage(mm("<red>❌ Category không còn tồn tại.</red>"));
			openItemManagement(player, draft.page());
			return;
		}

		var duplicate = store.findBySimilarItem(draft.itemStack());
		if (duplicate.isPresent()) {
			player.sendMessage(mm("<red>❌ Item này đã có trong shop với id <white>" + duplicate.get().id() + "</white>.</red>"));
			openItemManagement(player, draft.page());
			return;
		}

		ShopItem created = ShopItem.fromItemStackAutoId(draft.category(), draft.buyPrice(), draft.sellPrice(), draft.buyLimit(), draft.sellLimit(), draft.itemStack());
		store.upsert(created);
		player.sendMessage(mm("<green>✔ Đã tạo mặt hàng <white>" + created.id() + "</white>.</green>"));
		openItemManagement(player, draft.page());
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

	private ItemStack confirmButton(ShopItem shopItem, TradeMode mode, int amount) {
		int displayAmount = Math.max(1, Math.min(64, amount));
		double total = mode == TradeMode.BUY ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;
		if (mode == TradeMode.BUY) {
			ItemStack stack = item(Material.EMERALD, "<green>✔ Xác nhận mua", List.of(
				line(LunaPalette.SUCCESS_500, "♦ Số lượng: <white>" + amount),
				line(LunaPalette.SUCCESS_500, "💰 Tổng thanh toán: <gold>" + service.formatMoney(total)),
				"",
				actionLine("Chuột trái", "mua ngay")
			));
			stack.setAmount(displayAmount);
			return stack;
		}

		ItemStack stack = item(Material.GOLD_INGOT, "<yellow>✔ Xác nhận bán", List.of(
			line(LunaPalette.WARNING_500, "♦ Số lượng: <white>" + amount),
			line(LunaPalette.WARNING_500, "💵 Tổng nhận: <gold>" + service.formatMoney(total)),
			"",
			actionLine("Chuột trái", "bán ngay"),
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
					: actionLine("Chuột trái", "đổi sang mua"),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Mua theo số lượng bên dưới")
			));
		}

		return item(active ? Material.ORANGE_DYE : Material.GRAY_DYE, active ? "<yellow>▶ Chế độ Bán" : "<white>Chuyển sang Bán", List.of(
			active
				? line(LunaPalette.WARNING_500, "Đang ở chế độ bán vật phẩm")
				: actionLine("Chuột trái", "đổi sang bán"),
			"",
			plainLine(LunaPalette.NEUTRAL_100, "Bán theo số lượng bên dưới")
		));
	}

	private ItemStack amountButton(ShopItem shopItem, TradeMode mode, int amount) {
		double total = mode == TradeMode.BUY ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;
		int displayAmount = Math.max(1, Math.min(64, amount));
		String actionText = mode == TradeMode.BUY
			? actionLine("Chuột trái", "mua ngay")
			: actionLine("Chuột trái", "bán ngay");
		if (mode == TradeMode.BUY) {
			ItemStack stack = item(Material.PAPER, "<aqua>Mua nhanh <white>" + amount, List.of(
				line(LunaPalette.SUCCESS_500, "♦ Số lượng: <white>" + amount),
				line(LunaPalette.SUCCESS_500, "💰 Tổng thanh toán: <gold>" + service.formatMoney(total)),
				"",
				actionText,
				plainLine(LunaPalette.NEUTRAL_500, "số lượng này")
			));
			stack.setAmount(displayAmount);
			return stack;
		}

		ItemStack stack = item(Material.PAPER, "<yellow>Bán nhanh <white>" + amount, List.of(
			line(LunaPalette.WARNING_500, "♦ Số lượng: <white>" + amount),
			line(LunaPalette.WARNING_500, "💵 Tổng nhận: <gold>" + service.formatMoney(total)),
			"",
			actionText,
			plainLine(LunaPalette.NEUTRAL_500, "số lượng này")
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
			plainLine(LunaPalette.NEUTRAL_500, "▶ <color:" + LunaPalette.INFO_300 + "><bold>Chuột trái</bold></color>: nhấn nhiều lần"),
			plainLine(LunaPalette.NEUTRAL_500, "để chỉnh nhanh hơn")
		));
		stack.setAmount(Math.max(1, Math.min(64, Math.abs(delta))));
		return stack;
	}

	private ItemStack quickSellAllButton(Player player, ShopItem shopItem, int ownedAmount) {
		int sellAmount = service.capSellAmount(player, shopItem, Math.max(0, ownedAmount));
		double expected = shopItem.sellPrice() * Math.max(0, sellAmount);
		if (ownedAmount <= 0) {
			return item(Material.HOPPER, "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
				line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>0"),
				line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(0)),
				"",
				plainLine(LunaPalette.WARNING_500, "⚠ Bạn chưa có item tương tự"),
				plainLine(LunaPalette.WARNING_500, "trong túi đồ")
			));
		}

		if (sellAmount <= 0) {
			return item(Material.HOPPER, "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
				line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>0"),
				line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(0)),
				"",
				plainLine(LunaPalette.WARNING_500, "⚠ Đã đạt giới hạn bán hôm nay"),
				plainLine(LunaPalette.WARNING_500, "⏳ Reset sau: <white>" + service.tradeLimitResetDuration())
			));
		}

		return item(Material.HOPPER, "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
			line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>" + sellAmount),
			line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(expected)),
			"",
			actionLine("Chuột trái", "xác nhận bán toàn bộ")
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

	private void showLimitReachedAlert(Player player, GuiView view, int slot, TradeMode mode, ShopItem shopItem, ItemStack restoreItem) {
		String modeText = mode == TradeMode.BUY ? "mua" : "bán";
		int maxLimit = mode == TradeMode.BUY ? shopItem.buyTradeLimit() : shopItem.sellTradeLimit();
		view.setItem(slot, item(Material.ORANGE_STAINED_GLASS_PANE, "<color:" + LunaPalette.WARNING_500 + ">⚠ Hết hạn mức " + modeText + "</color>", List.of(
			line(LunaPalette.WARNING_500, "♦ Hạn mức ngày: <white>" + (maxLimit > 0 ? maxLimit : 0)),
			line(LunaPalette.WARNING_500, "⏳ Reset sau: <white>" + service.tradeLimitResetDuration())
		)));
		player.sendMessage(mm("<color:" + LunaPalette.WARNING_500 + ">⚠ Bạn đã đạt hạn mức " + modeText + " trong ngày. Reset sau <white>" + service.tradeLimitResetDuration() + "</white>.</color>"));
		scheduleAlertReset(player, view, slot, restoreItem);
	}

	private int effectiveTradeAmount(Player player, ShopItem shopItem, TradeMode mode, int requestedAmount) {
		if (mode == TradeMode.BUY) {
			return service.capBuyAmount(player, shopItem, requestedAmount);
		}

		return service.capSellAmount(player, shopItem, requestedAmount);
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

	private String actionLine(String button, String action) {
		return plainLine(LunaPalette.NEUTRAL_500,
			"▶ <color:" + LunaPalette.INFO_300 + "><bold>" + button + "</bold></color> để " + action);
	}

	private List<String> playerTradeLimitLore(Player player, ShopItem item) {
		if (!item.hasBuyTradeLimit() && !item.hasSellTradeLimit()) {
			return List.of();
		}

		int remainingBuy = service.remainingBuyLimit(player, item);
		int remainingSell = service.remainingSellLimit(player, item);
		ArrayList<String> lines = new ArrayList<>();
		lines.add(plainLine(LunaPalette.INFO_500, "⌚ Mua còn: " + limitValueText(remainingBuy, item.buyTradeLimit())));
		lines.add(plainLine(LunaPalette.INFO_500, "⌚ Bán còn: " + limitValueText(remainingSell, item.sellTradeLimit())));
		lines.add(plainLine(LunaPalette.INFO_300, "⏳ Reset hạn mức sau: " + service.tradeLimitResetDuration()));

		return lines;
	}

	private List<String> adminTradeLimitLore(ShopItem item) {
		if (!item.hasBuyTradeLimit() && !item.hasSellTradeLimit()) {
			return List.of();
		}

		ArrayList<String> lines = new ArrayList<>();
		lines.add("");
		lines.add(plainLine(LunaPalette.INFO_500, "⌚ Hạn mức mua/ngày: " + limitSettingText(item.buyTradeLimit())));
		lines.add(plainLine(LunaPalette.INFO_500, "⌚ Hạn mức bán/ngày: " + limitSettingText(item.sellTradeLimit())));
		lines.add(plainLine(LunaPalette.INFO_300, "⏳ Reset mỗi ngày Minecraft"));

		return lines;
	}

	private String limitValueText(int remaining, int maxLimit) {
		if (maxLimit <= 0) {
			return "Không giới hạn";
		}

		return Math.max(0, remaining) + "/" + maxLimit;
	}

	private String limitSettingText(int maxLimit) {
		if (maxLimit <= 0) {
			return "Không giới hạn";
		}

		return String.valueOf(maxLimit);
	}

	private int parseTradeLimitInput(String input) {
		if (input == null) {
			throw new IllegalArgumentException("missing");
		}

		String normalized = input.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank() || normalized.equals("none") || normalized.equals("off") || normalized.equals("unlimited")) {
			return 0;
		}

		int value;
		try {
			value = Integer.parseInt(normalized);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("invalid", exception);
		}
		if (value < 0) {
			throw new IllegalArgumentException("negative");
		}

		return value;
	}

	private List<Component> appendShopLore(ItemMeta meta, List<String> shopLoreLines) {
		ArrayList<Component> mergedLore = new ArrayList<>();
		List<Component> originalLore = meta.lore();
		if (originalLore != null && !originalLore.isEmpty()) {
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

	private Component tradeTitleWithAmount(int amount) {
		return LunaUi.guiTitleBreadcrumb("Luna Shop", "Giao Dịch").append(mm(
			" <color:" + LunaPalette.NEUTRAL_500 + ">•</color> <color:" + LunaPalette.NEUTRAL_700 + ">"
				+ amount
				+ "</color>"
		));
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

	private List<ShopItem> sortItems(List<ShopItem> items, SortField sortField, boolean ascending) {
		ArrayList<ShopItem> sorted = new ArrayList<>(items);
		Comparator<ShopItem> comparator = switch (sortField) {
			case ADDED_DATE -> Comparator.comparingLong(ShopItem::addedDate);
			case BUY_PRICE -> Comparator.comparingDouble(ShopItem::buyPrice);
			case SELL_PRICE -> Comparator.comparingDouble(ShopItem::sellPrice);
			case NAME -> Comparator.comparing(this::itemSortName, String.CASE_INSENSITIVE_ORDER);
			case ID -> Comparator.comparing(ShopItem::id, String.CASE_INSENSITIVE_ORDER);
		};

		if (!ascending) {
			comparator = comparator.reversed();
		}

		sorted.sort(comparator.thenComparing(ShopItem::id, String.CASE_INSENSITIVE_ORDER));
		return sorted;
	}

	private String itemSortName(ShopItem item) {
		ItemStack stack = item.itemStack();
		if (!stack.hasItemMeta()) {
			return stack.getType().getKey().asString();
		}

		ItemMeta meta = stack.getItemMeta();
		if (meta.hasCustomName() && meta.customName() != null) {
			return plainText.serialize(meta.customName());
		}

		return stack.getType().getKey().asString();
	}

	private SortField nextSortField(SortField sortField) {
		SortField[] values = SortField.values();
		int next = (sortField.ordinal() + 1) % values.length;
		return values[next];
	}

	private String sortLabel(SortField sortField) {
		return switch (sortField) {
			case ADDED_DATE -> "Ngày thêm";
			case BUY_PRICE -> "Giá mua";
			case SELL_PRICE -> "Giá bán";
			case NAME -> "Tên";
			case ID -> "ID";
		};
	}

	private enum SortField {
		ADDED_DATE,
		BUY_PRICE,
		SELL_PRICE,
		NAME,
		ID
	}

	private enum TradeMode {
		BUY,
		SELL
	}

	private record BrowseContext(String category, String query, int page, boolean search, SortField sortField, boolean sortAscending) {
		static BrowseContext category(String category, int page) {
			return new BrowseContext(category, null, page, false, SortField.ADDED_DATE, false);
		}

		static BrowseContext search(String query, int page) {
			return new BrowseContext(null, query, page, true, SortField.ADDED_DATE, false);
		}

		static BrowseContext categorySearch(String category, String query, int page) {
			return new BrowseContext(category, query, page, true, SortField.ADDED_DATE, false);
		}

		BrowseContext withPage(int value) {
			return new BrowseContext(category, query, value, search, sortField, sortAscending);
		}

		BrowseContext withSortField(SortField value) {
			return new BrowseContext(category, query, 0, search, value, sortAscending);
		}

		BrowseContext toggleSortDirection() {
			return new BrowseContext(category, query, 0, search, sortField, !sortAscending);
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

	private enum ItemEditField {
		ID,
		BUY_PRICE,
		SELL_PRICE,
		CATEGORY,
		BUY_LIMIT,
		SELL_LIMIT
	}

	private record ItemEditorSession(String itemId, int page) {
	}

	private record ItemEditorTextPrompt(String itemId, int page, ItemEditField field) {
	}

	private record CreateItemDraft(
		int page,
		String category,
		double buyPrice,
		double sellPrice,
		int buyLimit,
		int sellLimit,
		ItemStack itemStack
	) {
		static CreateItemDraft initial(int page, ItemStack itemStack) {
			return new CreateItemDraft(page, "", 0D, 0D, 0, 0, itemStack);
		}

		CreateItemDraft withCategory(String value) {
			return new CreateItemDraft(page, value, buyPrice, sellPrice, buyLimit, sellLimit, itemStack);
		}

		CreateItemDraft withBuyPrice(double value) {
			return new CreateItemDraft(page, category, value, sellPrice, buyLimit, sellLimit, itemStack);
		}

		CreateItemDraft withSellPrice(double value) {
			return new CreateItemDraft(page, category, buyPrice, value, buyLimit, sellLimit, itemStack);
		}

		CreateItemDraft withBuyLimit(int value) {
			return new CreateItemDraft(page, category, buyPrice, sellPrice, value, sellLimit, itemStack);
		}

		CreateItemDraft withSellLimit(int value) {
			return new CreateItemDraft(page, category, buyPrice, sellPrice, buyLimit, value, itemStack);
		}
	}

	private enum AdminPromptType {
		CREATE_CATEGORY,
		RENAME_CATEGORY,
		CREATE_ITEM,
		EDIT_ITEM_PRICES,
		EDIT_ITEM_LIMITS,
		MOVE_ITEM_CATEGORY
	}

	private record AdminPrompt(AdminPromptType type, String primary, String secondary, int page) {
	}

}

