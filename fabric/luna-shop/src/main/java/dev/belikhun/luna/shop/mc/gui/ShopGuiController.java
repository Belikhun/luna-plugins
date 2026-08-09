package dev.belikhun.luna.shop.mc.gui;

import dev.belikhun.luna.core.api.gui.LunaPagination;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaLore;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import dev.belikhun.luna.core.mc.ui.LunaChestMenuBase;
import dev.belikhun.luna.core.mc.ui.LunaItems;
import dev.belikhun.luna.core.mc.ui.LunaMenuHost;
import dev.belikhun.luna.shop.api.ShopItemIds;
import dev.belikhun.luna.shop.api.ShopResult;
import dev.belikhun.luna.shop.api.ShopTransactionEntry;
import dev.belikhun.luna.shop.mc.model.ShopCategory;
import dev.belikhun.luna.shop.mc.model.ShopItem;
import dev.belikhun.luna.shop.mc.service.ShopService;
import dev.belikhun.luna.shop.mc.store.ShopItemStore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every screen the shop draws, for players and for admins alike.
 *
 * The Paper controller is the reference: the same slots, the same wording, the
 * same flow. What differs is only the plumbing - one {@link LunaMenuHost} per
 * kind of screen instead of Bukkit's inventory holders, and the core's chat
 * prompt service instead of a cancelled chat event.
 *
 * Screens are drawn fresh each time rather than mutated, which is what makes
 * "reopen the page I was on" the answer to every action.
 */
public final class ShopGuiController {
	private static final int PAGE_SIZE = 45;
	private static final int HISTORY_PAGE_SIZE = 45;
	private static final int GUI_ROWS = 6;
	private static final int CONFIRM_ROWS = 3;
	private static final int[] QUICK_AMOUNT_SLOTS = {28, 29, 30, 32, 33, 34};
	private static final int[] QUICK_AMOUNTS = {1, 4, 8, 16, 32, 64};
	private static final int[] DECREASE_SLOTS = {36, 37, 38, 39};
	private static final int[] DECREASE_VALUES = {-8, -4, -2, -1};
	private static final int[] INCREASE_SLOTS = {41, 42, 43, 44};
	private static final int[] INCREASE_VALUES = {1, 2, 4, 8};
	private static final int CONFIRM_SLOT = 40;
	private static final int MAX_TRADE_AMOUNT = 4096;

	private final MinecraftServer server;
	private final ShopService service;
	private final ShopItemStore store;
	private final ChatPrompts chatPrompts;
	private final NumberSelectorScreen numberSelector;
	private final LunaMenuHost mainHost;
	private final LunaMenuHost confirmHost;
	private final Map<UUID, UUID> pendingConfirmations;

	public ShopGuiController(MinecraftServer server, ShopService service, ShopItemStore store, ChatPrompts chatPrompts) {
		this.server = server;
		this.service = service;
		this.store = store;
		this.chatPrompts = chatPrompts;
		this.numberSelector = new NumberSelectorScreen(chatPrompts);
		this.mainHost = new LunaMenuHost(GUI_ROWS);
		this.confirmHost = new LunaMenuHost(CONFIRM_ROWS);
		this.pendingConfirmations = new ConcurrentHashMap<>();
	}

	public void forget(UUID playerId) {
		mainHost.forget(playerId);
		confirmHost.forget(playerId);
		numberSelector.forget(playerId);
		chatPrompts.cancel(playerId);
		pendingConfirmations.remove(playerId);
	}

	public void close() {
		mainHost.closeAll();
		confirmHost.closeAll();
		numberSelector.closeAll();
		pendingConfirmations.clear();
	}

	// ---------------------------------------------------------------- admin menus

	public void openManagementMenu(ServerPlayer player) {
		mainHost.open(player, breadcrumb("Luna Shop", "Quản Lý"), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			menu.setTopSlot(20, item("chest", "<gold>♦ Quản Lý Danh Mục", List.of(
				"<gray>● Quản lý category và icon đại diện.",
				"",
				actionLine("Chuột trái", "mở")
			)), () -> openCategoryManagement(player, 0));

			menu.setTopSlot(24, item("book", "<aqua>♦ Quản Lý Mặt Hàng", List.of(
				"<gray>● Quản lý item shop, giá và danh mục.",
				"",
				actionLine("Chuột trái", "mở")
			)), () -> openItemManagement(player, 0));

			menu.setTopSlot(49, nav("emerald", "<green>★ Mở Shop người chơi"), () -> openMainMenu(player, 0));
			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), player::closeContainer);
		});
	}

	public void openCategoryManagement(ServerPlayer player, int page) {
		List<ShopCategory> categories = store.allCategories();
		int maxPage = maxPage(categories.size());
		int currentPage = clampPage(page, maxPage);

		mainHost.open(player, breadcrumb("Luna Shop", "Quản Lý", "Danh Mục"), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(categories.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index++) {
				ShopCategory category = categories.get(index);
				List<String> lore = List.of(
					"<gray>№ <white>" + category.id(),
					"<gray>ℹ Hiển thị: " + displayCategory(category.id()),
					"<gray>♦ Số mặt hàng: <green>" + store.byCategory(category.id()).size(),
					"",
					actionLine("Chuột trái", "đổi icon từ item tay"),
					actionLine("Chuột phải", "đổi tên danh mục"),
					actionLine("Shift+Chuột phải", "xóa danh mục rỗng")
				);

				menu.setTopSlot(
					index - start,
					decorate(category.iconItem(server), "<gold>♦ </gold>" + displayCategory(category.id()), lore),
					click -> handleCategoryClick(player, category, currentPage, click.isShift(), click.isRight())
				);
			}

			fillFooter(menu);

			if (currentPage > 0) {
				menu.setTopSlot(45, nav("arrow", "<yellow>← Trang trước"), () -> openCategoryManagement(player, currentPage - 1));
			}

			if (currentPage < maxPage) {
				menu.setTopSlot(53, nav("arrow", "<yellow>Trang sau →"), () -> openCategoryManagement(player, currentPage + 1));
			}

			menu.setTopSlot(49, nav("anvil", "<aqua>➕ Tạo danh mục mới"), () -> beginAdminPrompt(player, AdminPromptType.CREATE_CATEGORY, null, currentPage));
			menu.setTopSlot(50, nav("book", "<yellow>Quản lý mặt hàng"), () -> openItemManagement(player, 0));
			menu.setTopSlot(52, nav("arrow", "<yellow>← Quay lại"), () -> openManagementMenu(player));
		});
	}

	private void handleCategoryClick(ServerPlayer player, ShopCategory category, int page, boolean shift, boolean right) {
		if (shift && right) {
			if (!store.deleteCategory(category.id(), null)) {
				tell(player, "<red>❌ Danh mục còn item, không thể xóa trực tiếp.</red>");
				return;
			}

			tell(player, "<green>✔ Đã xóa danh mục <white>" + category.id() + "</white>.</green>");
			openCategoryManagement(player, page);
			return;
		}

		if (right) {
			beginAdminPrompt(player, AdminPromptType.RENAME_CATEGORY, category.id(), page);
			return;
		}

		ItemStack hand = player.getMainHandItem();

		if (hand.isEmpty()) {
			tell(player, "<red>❌ Hãy cầm item trên tay để đặt icon danh mục.</red>");
			return;
		}

		store.upsertCategoryIcon(category.id(), hand);
		tell(player, "<green>✔ Đã cập nhật icon cho danh mục <white>" + category.id() + "</white>.</green>");
		openCategoryManagement(player, page);
	}

	public void openItemManagement(ServerPlayer player, int page) {
		List<ShopItem> items = store.all();
		int maxPage = maxPage(items.size());
		int currentPage = clampPage(page, maxPage);

		mainHost.open(player, breadcrumb("Luna Shop", "Quản Lý", "Mặt Hàng"), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(items.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index++) {
				ShopItem shopItem = items.get(index);
				List<String> lore = new ArrayList<>(List.of(
					"<gray>№ <white>" + shopItem.id(),
					"<gray>♦ Danh mục: " + displayCategory(shopItem.category()),
					"<green>💰 Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()),
					"<yellow>💵 Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice())
				));
				lore.addAll(adminTradeLimitLore(shopItem));
				lore.add("");
				lore.add(actionLine("Chuột trái", "mở trình chỉnh sửa"));

				menu.setTopSlot(
					index - start,
					decorate(shopItem.itemStack(server), null, lore),
					() -> openItemEditor(player, shopItem.id(), currentPage)
				);
			}

			fillFooter(menu);

			if (currentPage > 0) {
				menu.setTopSlot(45, nav("arrow", "<yellow>← Trang trước"), () -> openItemManagement(player, currentPage - 1));
			}

			if (currentPage < maxPage) {
				menu.setTopSlot(53, nav("arrow", "<yellow>Trang sau →"), () -> openItemManagement(player, currentPage + 1));
			}

			menu.setTopSlot(49, nav("anvil", "<aqua>➕ Tạo item mới"), () -> beginCreateItemFlow(player, currentPage));
			menu.setTopSlot(50, nav("chest", "<yellow>Quản lý danh mục"), () -> openCategoryManagement(player, 0));
			menu.setTopSlot(52, nav("arrow", "<yellow>← Quay lại"), () -> openManagementMenu(player));
		});
	}

	private void openItemEditor(ServerPlayer player, String itemId, int page) {
		ShopItem item = store.find(itemId).orElse(null);

		if (item == null) {
			tell(player, "<red>❌ Item không tồn tại.</red>");
			openItemManagement(player, page);
			return;
		}

		mainHost.open(player, breadcrumb("Luna Shop", "Quản Lý", "Sửa Mặt Hàng"), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			List<String> previewLore = new ArrayList<>(List.of(
				"<gray>№ <white>" + item.id(),
				"<gray>♦ Danh mục: " + displayCategory(item.category()),
				"<green>💰 Giá mua: <gold>" + service.formatMoney(item.buyPrice()),
				"<yellow>💵 Giá bán: <gold>" + service.formatMoney(item.sellPrice())
			));
			previewLore.addAll(adminTradeLimitLore(item));
			menu.setDecoration(13, decorate(item.itemStack(server), null, previewLore));

			menu.setTopSlot(28, item("name_tag", "<aqua>✎ Sửa ID", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <white>" + item.id() + "</white>"),
				actionLine("Chuột trái", "nhập ID mới bằng chat")
			)), () -> beginItemEditorTextPrompt(player, item.id(), page, ItemEditField.ID, item.id()));

			menu.setTopSlot(29, item("emerald", "<green>💰 Sửa giá mua", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <gold>" + service.formatMoney(item.buyPrice()) + "</gold>"),
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> beginItemEditorNumberSelector(player, item.id(), page, ItemEditField.BUY_PRICE, item.buyPrice()));

			menu.setTopSlot(30, item("gold_ingot", "<yellow>💵 Sửa giá bán", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <gold>" + service.formatMoney(item.sellPrice()) + "</gold>"),
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> beginItemEditorNumberSelector(player, item.id(), page, ItemEditField.SELL_PRICE, item.sellPrice()));

			menu.setTopSlot(31, item("chest", "<aqua>♦ Sửa danh mục", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: " + displayCategory(item.category())),
				actionLine("Chuột trái", "nhập category bằng chat")
			)), () -> beginItemEditorTextPrompt(player, item.id(), page, ItemEditField.CATEGORY, item.category()));

			menu.setTopSlot(32, item("lime_dye", "<green>⌚ Hạn mức mua", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <white>" + limitSettingText(item.buyTradeLimit()) + "</white>"),
				plainLine(LunaPalette.NEUTRAL_100, "Nhập <white>0</white> để bỏ giới hạn"),
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> beginItemEditorNumberSelector(player, item.id(), page, ItemEditField.BUY_LIMIT, item.buyTradeLimit()));

			menu.setTopSlot(33, item("orange_dye", "<yellow>⌚ Hạn mức bán", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <white>" + limitSettingText(item.sellTradeLimit()) + "</white>"),
				plainLine(LunaPalette.NEUTRAL_100, "Nhập <white>0</white> để bỏ giới hạn"),
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> beginItemEditorNumberSelector(player, item.id(), page, ItemEditField.SELL_LIMIT, item.sellTradeLimit()));

			menu.setTopSlot(34, item("anvil", "<aqua>🔧 Cập nhật metadata", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Lấy item đang cầm làm metadata mới"),
				actionLine("Chuột trái", "cập nhật ngay")
			)), () -> updateItemMetadata(player, item, page));

			menu.setTopSlot(39, item("barrier", "<red>❌ Xóa mặt hàng", List.of(
				plainLine(LunaPalette.DANGER_500, "Hành động không thể hoàn tác"),
				actionLine("Chuột trái", "xác nhận xóa")
			)), () -> openConfirmationDialog(
				player,
				"<red>⚠ Xác nhận xóa mặt hàng",
				List.of(
					"<gray>Bạn sắp xóa item shop:",
					"<white>" + item.id(),
					"",
					"<red>Hành động này không thể hoàn tác."
				),
				() -> {
					store.remove(item.id());
					tell(player, "<green>✔ Đã xóa item <white>" + item.id() + "</white> khỏi shop.</green>");
					openItemManagement(player, page);
				},
				() -> openItemEditor(player, item.id(), page)
			));

			menu.setTopSlot(49, nav("arrow", "<yellow>← Quay lại danh sách"), () -> openItemManagement(player, page));
			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), player::closeContainer);
		});
	}

	private void updateItemMetadata(ServerPlayer player, ShopItem item, int page) {
		ItemStack hand = player.getMainHandItem();

		if (hand.isEmpty()) {
			tell(player, "<red>❌ Hãy cầm item trên tay để cập nhật metadata.</red>");
			openItemEditor(player, item.id(), page);
			return;
		}

		store.upsert(ShopItem.fromItemStack(
			server,
			item.id(),
			item.category(),
			item.buyPrice(),
			item.sellPrice(),
			item.buyTradeLimit(),
			item.sellTradeLimit(),
			hand,
			item.addedDate()
		));

		tell(player, "<green>✔ Đã cập nhật metadata cho item <white>" + item.id() + "</white>.</green>");
		openItemEditor(player, item.id(), page);
	}

	// ---------------------------------------------------------------- player menus

	public void openMainMenu(ServerPlayer player, int page) {
		List<ShopCategory> categories = store.allCategories();
		int maxPage = maxPage(categories.size());
		int currentPage = clampPage(page, maxPage);

		mainHost.open(player, compactTitle("Cửa Hàng"), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(categories.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index++) {
				ShopCategory category = categories.get(index);
				List<String> lore = List.of(
					"<gray>♦ Số mặt hàng: <green>" + store.byCategory(category.id()).size(),
					actionLine("Chuột trái", "mở danh mục này")
				);

				menu.setTopSlot(
					index - start,
					decorate(category.iconItem(server), "<gold>♦ </gold>" + displayCategory(category.id()), lore),
					() -> openCategoryMenu(player, category.id(), 0)
				);
			}

			fillFooter(menu);

			if (currentPage > 0) {
				menu.setTopSlot(45, nav("arrow", "<yellow>← Trang trước"), () -> openMainMenu(player, currentPage - 1));
			}

			if (currentPage < maxPage) {
				menu.setTopSlot(53, nav("arrow", "<yellow>Trang sau →"), () -> openMainMenu(player, currentPage + 1));
			}

			menu.setTopSlot(49, nav("compass", "<aqua>🔍 Tìm kiếm mặt hàng"), () -> beginSearch(player, null));
			menu.setTopSlot(50, nav("book", "<yellow>⌚ Lịch sử giao dịch"), () -> openTransactionHistory(player, 0));
			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), player::closeContainer);
		});
	}

	public void openCategoryMenu(ServerPlayer player, String category, int page) {
		openItemList(player, BrowseContext.category(category, page));
	}

	public void openSearchMenu(ServerPlayer player, String query, int page) {
		openItemList(player, BrowseContext.search(query, page));
	}

	private void openItemList(ServerPlayer player, BrowseContext context) {
		List<ShopItem> items = itemsFor(context);
		List<ShopItem> sortedItems = sortItems(items, context.sortField(), context.sortAscending());
		int maxPage = maxPage(sortedItems.size());
		int currentPage = clampPage(context.page(), maxPage);
		BrowseContext pageContext = context.withPage(currentPage);

		mainHost.open(player, titleFor(context), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(sortedItems.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index++) {
				ShopItem shopItem = sortedItems.get(index);
				List<String> lore = new ArrayList<>(List.of(
					"<gray>№ <white>" + shopItem.id(),
					"<gray>♦ Danh mục: " + displayCategory(shopItem.category()),
					"<green>💰 Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()),
					"<yellow>💵 Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice()),
					""
				));
				lore.addAll(playerTradeLimitLore(player, shopItem));
				lore.add("");
				lore.add(actionLine("Chuột trái", "mua"));
				lore.add(actionLine("Chuột phải", "bán"));

				menu.setTopSlot(
					index - start,
					decorate(shopItem.itemStack(server), null, lore),
					click -> openTradeMenu(player, new TradeSession(
						shopItem.id(),
						click.isRight() ? TradeMode.SELL : TradeMode.BUY,
						1,
						pageContext
					))
				);
			}

			fillFooter(menu);

			if (currentPage > 0) {
				menu.setTopSlot(45, nav("arrow", "<yellow>← Trang trước"), () -> openItemList(player, pageContext.withPage(currentPage - 1)));
			}

			if (currentPage < maxPage) {
				menu.setTopSlot(53, nav("arrow", "<yellow>Trang sau →"), () -> openItemList(player, pageContext.withPage(currentPage + 1)));
			}

			menu.setTopSlot(47, item("hopper", "<aqua>⚙ Sắp xếp theo", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Tiêu chí hiện tại: <yellow>" + sortLabel(context.sortField()) + "</yellow>"),
				actionLine("Chuột trái", "đổi tiêu chí")
			)), () -> openItemList(player, pageContext.withSortField(nextSortField(context.sortField()))));

			menu.setTopSlot(48, item("comparator", "<aqua>⇅ Thứ tự", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Hiện tại: <yellow>" + (context.sortAscending() ? "Tăng dần" : "Giảm dần") + "</yellow>"),
				actionLine("Chuột trái", "đảo thứ tự")
			)), () -> openItemList(player, pageContext.toggleSortDirection()));

			if (context.search()) {
				menu.setTopSlot(49, nav("compass", "<aqua>🔍 Tìm kiếm mới"), () -> beginSearch(player, null));
				menu.setTopSlot(50, nav("chest", "<yellow>Quay về danh mục"), () -> openMainMenu(player, 0));
			} else {
				menu.setTopSlot(49, nav("compass", "<aqua>🔍 Tìm trong danh mục"), () -> beginSearch(player, context.category()));
				menu.setTopSlot(50, nav("chest", "<yellow>Danh mục chính"), () -> openMainMenu(player, 0));
			}

			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), player::closeContainer);
		});
	}

	private List<ShopItem> itemsFor(BrowseContext context) {
		if (context.search()) {
			if (context.category() != null) {
				return store.searchInCategory(context.category(), context.query());
			}

			return store.search(context.query());
		}

		if (context.category() != null) {
			return store.byCategory(context.category());
		}

		return store.all();
	}

	private Component titleFor(BrowseContext context) {
		if (context.search()) {
			if (context.category() != null) {
				return compactTitle("Tìm kiếm: " + prettyCategory(context.category()));
			}

			return compactTitle("Tìm kiếm: " + (context.query() == null ? "" : context.query()));
		}

		return compactTitle("Danh mục: " + prettyCategory(context.category()));
	}

	// ---------------------------------------------------------------- trading

	private void openTradeMenu(ServerPlayer player, TradeSession session) {
		ShopItem shopItem = store.find(session.itemId()).orElse(null);

		if (shopItem == null) {
			tell(player, "<red>❌ Không tìm thấy vật phẩm này trong shop.</red>");
			openMainMenu(player, 0);
			return;
		}

		int amount = clampAmount(session.amount());
		TradeSession normalized = session.withAmount(amount);

		mainHost.open(player, compactTitle("Giao Dịch: " + amount), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			drawTradePreview(player, menu, shopItem, amount);

			menu.setTopSlot(20, modeButton(TradeMode.BUY, normalized.mode() == TradeMode.BUY), () -> openTradeMenu(player, normalized.withMode(TradeMode.BUY)));
			menu.setTopSlot(24, modeButton(TradeMode.SELL, normalized.mode() == TradeMode.SELL), () -> openTradeMenu(player, normalized.withMode(TradeMode.SELL)));

			for (int index = 0; index < QUICK_AMOUNTS.length; index++) {
				int amountValue = QUICK_AMOUNTS[index];
				menu.setTopSlot(
					QUICK_AMOUNT_SLOTS[index],
					amountButton(shopItem, normalized.mode(), amountValue),
					() -> attemptTrade(player, shopItem, normalized.withAmount(amountValue))
				);
			}

			for (int index = 0; index < DECREASE_VALUES.length; index++) {
				int delta = DECREASE_VALUES[index];
				menu.setTopSlot(DECREASE_SLOTS[index], adjustButton(delta), () -> openTradeMenu(player, normalized.withAmount(clampAmount(amount + delta))));
			}

			for (int index = 0; index < INCREASE_VALUES.length; index++) {
				int delta = INCREASE_VALUES[index];
				menu.setTopSlot(INCREASE_SLOTS[index], adjustButton(delta), () -> openTradeMenu(player, normalized.withAmount(clampAmount(amount + delta))));
			}

			menu.setTopSlot(CONFIRM_SLOT, confirmButton(shopItem, normalized.mode(), amount), () -> attemptTrade(player, shopItem, normalized));

			menu.setTopSlot(49, item("name_tag", "<aqua>✦ Nhập thủ công", List.of(
				line(LunaPalette.INFO_500, "ℹ Mở bộ chọn số lượng"),
				"",
				line(LunaPalette.NEUTRAL_100, "Bước chỉnh: <white>1..512</white>"),
				plainLine(LunaPalette.NEUTRAL_100, "Có thể nhập trực tiếp qua chat")
			)), () -> beginTradeAmountSelector(player, normalized));

			menu.setTopSlot(45, item("arrow", "<yellow>← Quay lại", List.of(
				plainLine(LunaPalette.WARNING_500, "Quay về danh sách trước"),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Số lượng vừa chọn vẫn giữ")
			)), () -> openItemList(player, normalized.context()));

			drawQuickSellAll(player, menu, shopItem, normalized);

			menu.setTopSlot(52, item("oak_door", "<red>Đóng", List.of(
				plainLine(LunaPalette.DANGER_500, "Thoát giao diện giao dịch")
			)), player::closeContainer);
		});
	}

	private void drawTradePreview(ServerPlayer player, LunaChestMenuBase menu, ShopItem shopItem, int amount) {
		int cappedBuyAmount = service.capBuyAmount(player, shopItem, amount);
		int cappedSellAmount = service.capSellAmount(player, shopItem, amount);

		List<String> lore = new ArrayList<>(List.of(
			"<white>№ <yellow>" + shopItem.id(),
			"<white>♦ Danh mục: " + displayCategory(shopItem.category()),
			"<white>♦ Số lượng: <yellow>" + amount,
			"<green>💰 Tổng mua (áp dụng): <gold>" + service.formatMoney(shopItem.buyPrice() * cappedBuyAmount),
			"<yellow>💵 Tổng bán (áp dụng): <gold>" + service.formatMoney(shopItem.sellPrice() * cappedSellAmount),
			"",
			"<white>💰 Số dư hiện tại: <yellow>" + service.formatMoney(service.economy().balance(player))
		));
		lore.add("");
		lore.addAll(playerTradeLimitLore(player, shopItem));

		ItemStack preview = decorate(shopItem.itemStack(server), null, lore);
		preview.setCount(Math.max(1, Math.min(64, amount)));
		menu.setDecoration(13, preview);
	}

	private void drawQuickSellAll(ServerPlayer player, LunaChestMenuBase menu, ShopItem shopItem, TradeSession session) {
		if (session.mode() != TradeMode.SELL) {
			menu.setDecoration(23, item("gray_dye", "<white>★ Bán nhanh tất cả", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Chỉ dùng trong chế độ <yellow>Bán</yellow>"),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Đổi mode để bán nhanh hơn")
			)));
			return;
		}

		int owned = service.countSimilar(player.getInventory(), shopItem.itemStack(server));

		menu.setTopSlot(23, quickSellAllButton(player, shopItem, owned), () -> {
			int sellAmount = service.countSimilar(player.getInventory(), shopItem.itemStack(server));

			if (sellAmount <= 0) {
				tell(player, "<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>");
				return;
			}

			int effectiveSellAmount = service.capSellAmount(player, shopItem, sellAmount);

			if (effectiveSellAmount <= 0) {
				tellLimitReached(player, TradeMode.SELL);
				return;
			}

			openConfirmationDialog(
				player,
				"<yellow>⚠ Xác nhận bán nhanh toàn bộ",
				List.of(
					"<gray>Vật phẩm: <white>" + shopItem.id(),
					"<gray>Số lượng sẽ bán: <white>" + effectiveSellAmount,
					"<gray>Tiền dự kiến nhận: <gold>" + service.formatMoney(shopItem.sellPrice() * effectiveSellAmount)
				),
				() -> {
					ShopResult result = service.sellAllSimilar(player, shopItem);
					tell(player, result.message());
					openTradeMenu(player, session);
				},
				() -> openTradeMenu(player, session)
			);
		});
	}

	/**
	 * Check what a trade would do, then either do it or say why not.
	 *
	 * A buy costing more than half of what the player has stops for a confirmation
	 * first. That threshold is the one guard against a mis-click on a quick-amount
	 * button emptying an account, and it is deliberately about the share of the
	 * balance rather than an absolute price.
	 */
	private void attemptTrade(ServerPlayer player, ShopItem shopItem, TradeSession session) {
		int effectiveAmount = session.mode() == TradeMode.BUY
			? service.capBuyAmount(player, shopItem, session.amount())
			: service.capSellAmount(player, shopItem, session.amount());

		if (effectiveAmount <= 0) {
			tellLimitReached(player, session.mode());
			return;
		}

		if (session.mode() == TradeMode.BUY) {
			double total = shopItem.buyPrice() * effectiveAmount;
			double balance = service.economy().balance(player);

			if (balance < total) {
				tell(player, "<color:" + LunaPalette.WARNING_500 + ">⚠ Bạn không đủ tiền để thực hiện giao dịch này.</color>");
				return;
			}

			if (balance > 0D && total > balance * 0.5D) {
				openConfirmationDialog(
					player,
					"<yellow>⚠ Xác nhận mua đơn lớn",
					List.of(
						"<gray>Tổng tiền: <gold>" + service.formatMoney(total),
						"<gray>Số dư hiện tại: <white>" + service.formatMoney(balance),
						"<gray>Lệnh mua này vượt <white>50%</white> số dư của bạn."
					),
					() -> completeTrade(player, shopItem, session),
					() -> openTradeMenu(player, session)
				);
				return;
			}
		}

		if (session.mode() == TradeMode.SELL && service.countSimilar(player.getInventory(), shopItem.itemStack(server)) < effectiveAmount) {
			tell(player, "<color:" + LunaPalette.WARNING_500 + ">⚠ Bạn không đủ vật phẩm tương ứng để bán.</color>");
			return;
		}

		completeTrade(player, shopItem, session);
	}

	private void completeTrade(ServerPlayer player, ShopItem shopItem, TradeSession session) {
		ShopResult result = session.mode() == TradeMode.BUY
			? service.buy(player, shopItem, session.amount())
			: service.sell(player, shopItem, session.amount());

		tell(player, result.message());
		openTradeMenu(player, session);
	}

	private void tellLimitReached(ServerPlayer player, TradeMode mode) {
		String modeText = mode == TradeMode.BUY ? "mua" : "bán";
		tell(player, "<color:" + LunaPalette.WARNING_500 + ">⚠ Bạn đã đạt hạn mức " + modeText
			+ " trong ngày. Reset <white>" + service.tradeLimitResetTimeText() + "</white>.</color>");
	}

	// ---------------------------------------------------------------- history

	public void openTransactionHistory(ServerPlayer player, int page) {
		openTransactionHistory(player, player.getUUID(), player.getName().getString(), page, false);
	}

	public void openTransactionHistoryAdmin(ServerPlayer admin, UUID targetUuid, String targetName, int page) {
		openTransactionHistory(admin, targetUuid, targetName, page, true);
	}

	private void openTransactionHistory(ServerPlayer viewer, UUID targetUuid, String targetName, int page, boolean adminView) {
		String normalizedTargetName = targetName == null || targetName.isBlank() ? "Unknown" : targetName;

		if (!service.isTransactionHistoryEnabled()) {
			renderHistoryUnavailable(viewer, adminView);
			return;
		}

		renderHistoryLoading(viewer, adminView);

		UUID viewerId = viewer.getUUID();

		service.transactionHistoryPageAsync(targetUuid, page, HISTORY_PAGE_SIZE).whenComplete((historyPage, throwable) -> server.execute(() -> {
			ServerPlayer current = server.getPlayerList().getPlayer(viewerId);

			if (current == null) {
				return;
			}

			if (throwable != null || historyPage == null) {
				tell(current, "<red>❌ Không thể tải lịch sử giao dịch lúc này.</red>");
				return;
			}

			renderTransactionHistory(current, targetUuid, normalizedTargetName, adminView, historyPage);
		}));
	}

	private void renderHistoryUnavailable(ServerPlayer viewer, boolean adminView) {
		mainHost.open(viewer, breadcrumb("Luna Shop", "Lịch Sử"), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			menu.setDecoration(22, item("barrier", "<red>❌ Database chưa bật", List.of(
				plainLine(LunaPalette.WARNING_500, "Lịch sử giao dịch yêu cầu database"),
				plainLine(LunaPalette.NEUTRAL_100, "Hãy bật LunaCore database API")
			)));

			drawHistoryExit(viewer, menu, adminView);
		});
	}

	private void renderHistoryLoading(ServerPlayer viewer, boolean adminView) {
		mainHost.open(viewer, breadcrumb("Luna Shop", "Lịch Sử"), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			menu.setDecoration(22, item("clock", "<yellow>⌛ Đang tải lịch sử", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Vui lòng chờ trong giây lát")
			)));

			drawHistoryExit(viewer, menu, adminView);
		});
	}

	private void renderTransactionHistory(
		ServerPlayer viewer,
		UUID targetUuid,
		String normalizedTargetName,
		boolean adminView,
		ShopService.ShopHistoryPage historyPage
	) {
		mainHost.open(viewer, breadcrumb("Luna Shop", "Lịch Sử"), menu -> {
			menu.clearTopSlots();

			List<ShopTransactionEntry> entries = historyPage.entries();

			for (int index = 0; index < entries.size() && index < HISTORY_PAGE_SIZE; index++) {
				ShopTransactionEntry entry = entries.get(index);
				menu.setDecoration(index, historyItem(entry));
			}

			fillFooter(menu);

			if (historyPage.currentPage() > 0) {
				menu.setTopSlot(45, nav("arrow", "<yellow>← Trang trước"), () ->
					openTransactionHistory(viewer, targetUuid, normalizedTargetName, historyPage.currentPage() - 1, adminView));
			}

			if (historyPage.currentPage() < historyPage.maxPage()) {
				menu.setTopSlot(53, nav("arrow", "<yellow>Trang sau →"), () ->
					openTransactionHistory(viewer, targetUuid, normalizedTargetName, historyPage.currentPage() + 1, adminView));
			}

			drawHistoryExit(viewer, menu, adminView);

			menu.setDecoration(50, item("paper", "<aqua>ℹ Thông tin", List.of(
				plainLine(LunaPalette.NEUTRAL_100, "Người chơi: <yellow>" + normalizedTargetName + "</yellow>"),
				plainLine(LunaPalette.NEUTRAL_100, "Tổng giao dịch: <yellow>" + historyPage.total() + "</yellow>"),
				plainLine(LunaPalette.NEUTRAL_100, "Trang: <yellow>" + (historyPage.currentPage() + 1) + "/" + (historyPage.maxPage() + 1) + "</yellow>")
			)));
		});
	}

	private ItemStack historyItem(ShopTransactionEntry entry) {
		boolean buy = entry.action().equalsIgnoreCase("BUY");
		String material = entry.success() ? "lime_dye" : "red_dye";
		String actionText = buy ? "MUA" : "BÁN";
		String actionColor = buy ? LunaPalette.SUCCESS_500 : LunaPalette.WARNING_500;
		String resultColor = entry.success() ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500;
		String statusText = entry.success() ? "THÀNH CÔNG" : "THẤT BẠI";
		String shortId = entry.transactionId().substring(0, Math.min(8, entry.transactionId().length()));

		List<String> lore = new ArrayList<>(List.of(
			"<white>№ Item: <yellow>" + entry.itemId(),
			"<white>♦ Danh mục: " + displayCategory(entry.category()),
			"<white>♦ Số lượng: <yellow>" + entry.amount(),
			"<white>♦ Đơn giá: <gold>" + service.formatMoney(entry.unitPrice()),
			"<white>♦ Tổng tiền: <gold>" + service.formatMoney(entry.totalPrice()),
			"<white>♦ Kết quả: <color:" + resultColor + ">" + statusText + "</color>",
			"<white>⌚ Thời gian: <gray>" + Formatters.date(Instant.ofEpochMilli(entry.createdAt()))
		));

		if (!entry.success()) {
			lore.add("<white>⚠ Lý do: <color:" + LunaPalette.DANGER_500 + ">" + entry.reason() + "</color>");
		}

		return item(material, "<color:" + actionColor + ">" + actionText + "</color> <white>#" + shortId + "</white>", lore);
	}

	private void drawHistoryExit(ServerPlayer viewer, LunaChestMenuBase menu, boolean adminView) {
		menu.setTopSlot(49, nav("chest", adminView ? "<yellow>Quay lại quản lý" : "<yellow>Danh mục chính"), () -> {
			if (adminView) {
				openManagementMenu(viewer);
				return;
			}

			openMainMenu(viewer, 0);
		});

		menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), viewer::closeContainer);
	}

	// ---------------------------------------------------------------- prompts

	private void beginSearch(ServerPlayer player, String category) {
		player.closeContainer();

		if (category == null) {
			tell(player, "<aqua>🔍 Nhập từ khóa tìm kiếm toàn shop. Gõ <white>huy</white> để hủy.</aqua>");
		} else {
			tell(player, "<aqua>🔍 Nhập từ khóa trong danh mục " + displayCategory(category) + "<aqua>. Gõ <white>huy</white> để hủy.</aqua>");
		}

		chatPrompts.await(player, query -> {
			if (isCancelWord(query)) {
				tell(player, "<yellow>ℹ Đã hủy tìm kiếm.</yellow>");

				if (category == null) {
					openMainMenu(player, 0);
				} else {
					openCategoryMenu(player, category, 0);
				}

				return;
			}

			if (category == null) {
				openSearchMenu(player, query, 0);
				return;
			}

			openItemList(player, BrowseContext.categorySearch(category, query, 0));
		});
	}

	private void beginItemEditorTextPrompt(ServerPlayer player, String itemId, int page, ItemEditField field, String initialValue) {
		String label = switch (field) {
			case ID -> "<aqua>Nhập ID mới";
			case CATEGORY -> "<aqua>Nhập category";
			default -> "<aqua>Nhập giá trị";
		};

		player.closeContainer();
		tell(player, label + " <gray>(hiện tại: <white>" + (initialValue == null ? "" : initialValue) + "</white>)</gray>");
		tell(player, "<aqua>✦ Nhập trên chat. Gõ <white>huy</white> để hủy.</aqua>");

		chatPrompts.await(player, input -> {
			if (isCancelWord(input)) {
				tell(player, "<yellow>ℹ Đã hủy chỉnh sửa.</yellow>");
				openItemEditor(player, itemId, page);
				return;
			}

			applyItemEditorInput(player, itemId, page, field, input);
		});
	}

	private void beginItemEditorNumberSelector(ServerPlayer player, String itemId, int page, ItemEditField field, double initialValue) {
		String title = switch (field) {
			case BUY_PRICE -> "<green>Nhập giá mua";
			case SELL_PRICE -> "<yellow>Nhập giá bán";
			case BUY_LIMIT -> "<color:#374151>Nhập hạn mức mua";
			case SELL_LIMIT -> "<color:#374151>Nhập hạn mức bán";
			default -> "<aqua>Nhập số";
		};

		boolean integerMode = field == ItemEditField.BUY_LIMIT || field == ItemEditField.SELL_LIMIT;
		double maxValue = integerMode ? 1_000_000D : 1_000_000_000D;

		NumberSelectorScreen.Request request = NumberSelectorScreen.Request
			.of(title, "Giá trị", (submitPlayer, value) -> {
				double normalized = integerMode ? Math.max(0D, Math.rint(value)) : Math.max(0D, value);
				String serialized = integerMode ? String.valueOf((int) Math.rint(normalized)) : String.valueOf(normalized);
				applyItemEditorInput(submitPlayer, itemId, page, field, serialized);
			}, closePlayer -> openItemEditor(closePlayer, itemId, page))
			.withDisplayMaterial("paper")
			.withInitialValue(initialValue)
			.withRange(0D, maxValue)
			.withIntegerMode(integerMode)
			.withNumberDisplayFormatter(value -> integerMode
				? String.valueOf((int) Math.rint(value))
				: service.formatMoney(value))
			.withUnit(integerMode ? "lượt" : "");

		numberSelector.open(player, request);
	}

	private void beginTradeAmountSelector(ServerPlayer player, TradeSession session) {
		NumberSelectorScreen.Request request = NumberSelectorScreen.Request
			.of(
				"<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">Luna Shop › Giao Dịch › Số Lượng</color>",
				"Số lượng giao dịch",
				(submitPlayer, value) -> openTradeMenu(submitPlayer, session.withAmount(clampAmount((int) Math.rint(value)))),
				closePlayer -> openTradeMenu(closePlayer, session)
			)
			.withDisplayMaterial("paper")
			.withInitialValue(session.amount())
			.withRange(1D, MAX_TRADE_AMOUNT)
			.withIntegerMode(true)
			.withNumberDisplayFormatter(value -> String.valueOf((int) Math.rint(value)))
			.withUnit("món");

		numberSelector.open(player, request);
	}

	private void applyItemEditorInput(ServerPlayer player, String itemId, int page, ItemEditField field, String input) {
		ShopItem item = store.find(itemId).orElse(null);

		if (item == null) {
			tell(player, "<red>❌ Item không tồn tại.</red>");
			openItemManagement(player, page);
			return;
		}

		try {
			ShopItem updated = switch (field) {
				case ID -> applyItemId(item, input);
				case BUY_PRICE -> item.withBuyPrice(parseNonNegativeDouble(input, "Giá mua phải là số >= 0."));
				case SELL_PRICE -> item.withSellPrice(parseNonNegativeDouble(input, "Giá bán phải là số >= 0."));
				case CATEGORY -> applyCategory(item, input);
				case BUY_LIMIT -> item.withBuyTradeLimit(parseTradeLimitInput(input));
				case SELL_LIMIT -> item.withSellTradeLimit(parseTradeLimitInput(input));
			};

			if (!updated.id().equals(item.id())) {
				store.remove(item.id());
			}

			store.upsert(updated);
			tell(player, "<green>✔ Đã cập nhật <white>" + updated.id() + "</white>.</green>");
			openItemEditor(player, updated.id(), page);
		} catch (IllegalArgumentException exception) {
			tell(player, "<red>❌ " + exception.getMessage() + "</red>");
			openItemEditor(player, item.id(), page);
		}
	}

	private ShopItem applyItemId(ShopItem item, String rawValue) {
		String normalized = ShopItemIds.normalizeId(rawValue);
		ShopItem duplicate = store.find(normalized).orElse(null);

		if (duplicate != null && !duplicate.id().equals(item.id())) {
			throw new IllegalArgumentException("ID này đã tồn tại trong shop.");
		}

		return item.withId(normalized);
	}

	private ShopItem applyCategory(ShopItem item, String rawValue) {
		String categoryId = ShopItemIds.normalizeCategory(rawValue);

		if (store.findCategory(categoryId).isEmpty()) {
			throw new IllegalArgumentException("Category không tồn tại.");
		}

		return item.withCategory(categoryId);
	}

	private void beginAdminPrompt(ServerPlayer player, AdminPromptType type, String primary, int page) {
		player.closeContainer();

		switch (type) {
			case CREATE_CATEGORY -> tell(player, "<aqua>✦ Nhập <white>ID danh mục</white> trên chat. Gõ <white>huy</white> để hủy.</aqua>");
			case RENAME_CATEGORY -> tell(player, "<aqua>✦ Nhập <white>ID mới</white> cho danh mục <white>" + primary + "</white>. Gõ <white>huy</white> để hủy.</aqua>");
		}

		chatPrompts.await(player, input -> {
			if (isCancelWord(input)) {
				tell(player, "<yellow>ℹ Đã hủy thao tác quản trị.</yellow>");
				openCategoryManagement(player, page);
				return;
			}

			handleAdminPrompt(player, type, primary, page, input);
		});
	}

	private void handleAdminPrompt(ServerPlayer player, AdminPromptType type, String primary, int page, String input) {
		switch (type) {
			case CREATE_CATEGORY -> {
				if (store.findCategory(input).isPresent()) {
					tell(player, "<red>❌ Danh mục đã tồn tại.</red>");
					openCategoryManagement(player, page);
					return;
				}

				ItemStack hand = player.getMainHandItem();

				if (hand.isEmpty()) {
					tell(player, "<red>❌ Hãy cầm item để làm icon danh mục.</red>");
					openCategoryManagement(player, page);
					return;
				}

				store.upsertCategory(ShopCategory.fromIcon(server, input, hand));
				tell(player, "<green>✔ Đã tạo danh mục <white>" + ShopItemIds.normalizeCategory(input) + "</white>.</green>");
				openCategoryManagement(player, page);
			}
			case RENAME_CATEGORY -> {
				if (store.findCategory(input).isPresent()) {
					tell(player, "<red>❌ Danh mục đích đã tồn tại.</red>");
					openCategoryManagement(player, page);
					return;
				}

				if (!store.renameCategory(primary, input)) {
					tell(player, "<red>❌ Không thể đổi tên danh mục.</red>");
					openCategoryManagement(player, page);
					return;
				}

				tell(player, "<green>✔ Đã đổi tên danh mục thành <white>" + ShopItemIds.normalizeCategory(input) + "</white>.</green>");
				openCategoryManagement(player, page);
			}
		}
	}

	// ---------------------------------------------------------------- item creation

	private void beginCreateItemFlow(ServerPlayer player, int page) {
		ItemStack hand = player.getMainHandItem();

		if (hand.isEmpty()) {
			tell(player, "<red>❌ Hãy cầm item trên tay để tạo mặt hàng.</red>");
			openItemManagement(player, page);
			return;
		}

		// the item is copied now, not read again later: the whole flow happens with
		// the screen closed, and nothing stops the player swapping what they hold
		ItemStack captured = hand.copy();
		player.closeContainer();
		tell(player, "<aqua>✦ Nhập <white>category</white> cho item mới. Gõ <white>huy</white> để hủy.</aqua>");
		askForCreateItemCategory(player, page, captured);
	}

	/** Ask until the answer names a category that exists, or the player gives up. */
	private void askForCreateItemCategory(ServerPlayer player, int page, ItemStack captured) {
		chatPrompts.await(player, input -> {
			if (isCancelWord(input)) {
				tell(player, "<yellow>ℹ Đã hủy tạo item.</yellow>");
				openItemManagement(player, page);
				return;
			}

			String category = ShopItemIds.normalizeCategory(input);

			if (store.findCategory(category).isEmpty()) {
				tell(player, "<red>❌ Category không tồn tại. Hãy nhập lại hoặc gõ <white>huy</white>.</red>");
				askForCreateItemCategory(player, page, captured);
				return;
			}

			openCreateItemBuyPriceSelector(player, CreateItemDraft.initial(page, captured).withCategory(category));
		});
	}

	private void openCreateItemBuyPriceSelector(ServerPlayer player, CreateItemDraft draft) {
		numberSelector.open(player, NumberSelectorScreen.Request
			.of(
				"<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">Tạo Item › Giá Mua</color>",
				"Giá mua",
				(submitPlayer, value) -> openCreateItemSellPriceSelector(submitPlayer, draft.withBuyPrice(Math.max(0D, value))),
				closePlayer -> cancelCreateItem(closePlayer, draft)
			)
			.withDisplayMaterial("emerald")
			.withInitialValue(Math.max(0D, draft.buyPrice()))
			.withRange(0D, 1_000_000_000D)
			.withIntegerMode(false)
			.withNumberDisplayFormatter(value -> service.formatMoney(value == null ? 0D : value)));
	}

	private void openCreateItemSellPriceSelector(ServerPlayer player, CreateItemDraft draft) {
		numberSelector.open(player, NumberSelectorScreen.Request
			.of(
				"<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">Tạo Item › Giá Bán</color>",
				"Giá bán",
				(submitPlayer, value) -> openCreateItemBuyLimitSelector(submitPlayer, draft.withSellPrice(Math.max(0D, value))),
				closePlayer -> cancelCreateItem(closePlayer, draft)
			)
			.withDisplayMaterial("gold_ingot")
			.withInitialValue(Math.max(0D, draft.sellPrice()))
			.withRange(0D, 1_000_000_000D)
			.withIntegerMode(false)
			.withNumberDisplayFormatter(value -> service.formatMoney(value == null ? 0D : value)));
	}

	private void openCreateItemBuyLimitSelector(ServerPlayer player, CreateItemDraft draft) {
		numberSelector.open(player, NumberSelectorScreen.Request
			.of(
				"<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">Tạo Item › Hạn Mức Mua</color>",
				"Hạn mức mua",
				(submitPlayer, value) -> openCreateItemSellLimitSelector(submitPlayer, draft.withBuyLimit((int) Math.max(0D, Math.rint(value)))),
				closePlayer -> cancelCreateItem(closePlayer, draft)
			)
			.withDisplayMaterial("lime_dye")
			.withInitialValue(Math.max(0, draft.buyLimit()))
			.withRange(0D, 1_000_000D)
			.withIntegerMode(true)
			.withNumberDisplayFormatter(value -> String.valueOf((int) Math.rint(value)))
			.withUnit("lượt"));
	}

	private void openCreateItemSellLimitSelector(ServerPlayer player, CreateItemDraft draft) {
		numberSelector.open(player, NumberSelectorScreen.Request
			.of(
				"<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">Tạo Item › Hạn Mức Bán</color>",
				"Hạn mức bán",
				(submitPlayer, value) -> finishCreateItem(submitPlayer, draft.withSellLimit((int) Math.max(0D, Math.rint(value)))),
				closePlayer -> cancelCreateItem(closePlayer, draft)
			)
			.withDisplayMaterial("orange_dye")
			.withInitialValue(Math.max(0, draft.sellLimit()))
			.withRange(0D, 1_000_000D)
			.withIntegerMode(true)
			.withNumberDisplayFormatter(value -> String.valueOf((int) Math.rint(value)))
			.withUnit("lượt"));
	}

	private void cancelCreateItem(ServerPlayer player, CreateItemDraft draft) {
		tell(player, "<yellow>ℹ Đã hủy tạo item.</yellow>");
		openItemManagement(player, draft.page());
	}

	private void finishCreateItem(ServerPlayer player, CreateItemDraft draft) {
		if (store.findCategory(draft.category()).isEmpty()) {
			tell(player, "<red>❌ Category không còn tồn tại.</red>");
			openItemManagement(player, draft.page());
			return;
		}

		Optional<ShopItem> duplicate = store.findBySimilarItem(draft.itemStack());

		if (duplicate.isPresent()) {
			tell(player, "<red>❌ Item này đã có trong shop với id <white>" + duplicate.get().id() + "</white>.</red>");
			openItemManagement(player, draft.page());
			return;
		}

		ShopItem created = ShopItem.fromItemStackAutoId(
			server,
			draft.category(),
			draft.buyPrice(),
			draft.sellPrice(),
			draft.buyLimit(),
			draft.sellLimit(),
			draft.itemStack()
		);

		store.upsert(created);
		tell(player, "<green>✔ Đã tạo mặt hàng <white>" + created.id() + "</white>.</green>");
		openItemManagement(player, draft.page());
	}

	// ---------------------------------------------------------------- confirmation

	/**
	 * A yes/no over whatever is open, on its own host so the screen underneath is
	 * not forgotten.
	 *
	 * The token is what makes a stale dialog harmless: a player who opened two in
	 * a row can only answer the newer one, and the older one's buttons do nothing.
	 */
	private void openConfirmationDialog(ServerPlayer player, String title, List<String> details, Runnable onConfirm, Runnable onCancel) {
		UUID playerId = player.getUUID();
		UUID token = UUID.randomUUID();
		pendingConfirmations.put(playerId, token);

		confirmHost.open(player, LunaTextComponents.mini(title), menu -> {
			menu.clearTopSlots();

			for (int slot = 0; slot < menu.containerSize(); slot++) {
				menu.setDecoration(slot, item("gray_stained_glass_pane", "<gray> ", List.of()));
			}

			menu.setDecoration(13, item("paper", "<yellow>Chi tiết xác nhận", details));

			menu.setTopSlot(11, item("lime_concrete", "<green>✔ Xác nhận", List.of("<gray>Thực hiện hành động này.")), () -> {
				if (!token.equals(pendingConfirmations.remove(playerId))) {
					return;
				}

				onConfirm.run();
			});

			menu.setTopSlot(15, item("red_concrete", "<red>❌ Hủy", List.of("<gray>Quay lại màn trước.")), () -> {
				if (!token.equals(pendingConfirmations.remove(playerId))) {
					return;
				}

				onCancel.run();
			});
		});
	}

	// ---------------------------------------------------------------- buttons

	private ItemStack confirmButton(ShopItem shopItem, TradeMode mode, int amount) {
		double total = mode == TradeMode.BUY ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;
		int displayAmount = Math.max(1, Math.min(64, amount));

		if (mode == TradeMode.BUY) {
			return item("emerald", "<green>✔ Xác nhận mua", List.of(
				line(LunaPalette.SUCCESS_500, "♦ Số lượng: <white>" + amount),
				line(LunaPalette.SUCCESS_500, "💰 Tổng thanh toán: <gold>" + service.formatMoney(total)),
				"",
				actionLine("Chuột trái", "mua ngay")
			), displayAmount);
		}

		return item("gold_ingot", "<yellow>✔ Xác nhận bán", List.of(
			line(LunaPalette.WARNING_500, "♦ Số lượng: <white>" + amount),
			line(LunaPalette.WARNING_500, "💵 Tổng nhận: <gold>" + service.formatMoney(total)),
			"",
			actionLine("Chuột trái", "bán ngay"),
			plainLine(LunaPalette.NEUTRAL_100, "số lượng đã chọn")
		), displayAmount);
	}

	private ItemStack modeButton(TradeMode mode, boolean active) {
		if (mode == TradeMode.BUY) {
			return item(active ? "lime_dye" : "gray_dye", active ? "<green>▶ Chế độ Mua" : "<white>Chuyển sang Mua", List.of(
				active
					? line(LunaPalette.SUCCESS_500, "Đang ở chế độ mua vật phẩm")
					: actionLine("Chuột trái", "đổi sang mua"),
				"",
				plainLine(LunaPalette.NEUTRAL_100, "Mua theo số lượng bên dưới")
			));
		}

		return item(active ? "orange_dye" : "gray_dye", active ? "<yellow>▶ Chế độ Bán" : "<white>Chuyển sang Bán", List.of(
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
		boolean buy = mode == TradeMode.BUY;

		return item("paper", (buy ? "<aqua>Mua nhanh <white>" : "<yellow>Bán nhanh <white>") + amount, List.of(
			line(buy ? LunaPalette.SUCCESS_500 : LunaPalette.WARNING_500, "♦ Số lượng: <white>" + amount),
			line(buy ? LunaPalette.SUCCESS_500 : LunaPalette.WARNING_500, (buy ? "💰 Tổng thanh toán: <gold>" : "💵 Tổng nhận: <gold>") + service.formatMoney(total)),
			"",
			actionLine("Chuột trái", buy ? "mua ngay" : "bán ngay"),
			plainLine(LunaPalette.NEUTRAL_500, "số lượng này")
		), displayAmount);
	}

	private ItemStack adjustButton(int delta) {
		String material = delta > 0 ? "lime_stained_glass_pane" : "red_stained_glass_pane";
		String modeText = delta > 0 ? "Tăng" : "Giảm";
		String prefix = delta > 0 ? "+" : "";

		return item(material, "<white>" + prefix + delta, List.of(
			line(delta > 0 ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500, "♦ " + modeText + " thêm <white>" + Math.abs(delta)),
			"",
			plainLine(LunaPalette.NEUTRAL_500, "▶ <color:" + LunaPalette.INFO_300 + "><bold>Chuột trái</bold></color>: nhấn nhiều lần"),
			plainLine(LunaPalette.NEUTRAL_500, "để chỉnh nhanh hơn")
		), Math.max(1, Math.min(64, Math.abs(delta))));
	}

	private ItemStack quickSellAllButton(ServerPlayer player, ShopItem shopItem, int ownedAmount) {
		int sellAmount = service.capSellAmount(player, shopItem, Math.max(0, ownedAmount));

		if (ownedAmount <= 0) {
			return item("hopper", "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
				line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>0"),
				line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(0)),
				"",
				plainLine(LunaPalette.WARNING_500, "⚠ Bạn chưa có item tương tự"),
				plainLine(LunaPalette.WARNING_500, "trong túi đồ")
			));
		}

		if (sellAmount <= 0) {
			return item("hopper", "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
				line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>0"),
				line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(0)),
				"",
				plainLine(LunaPalette.WARNING_500, "⚠ Đã đạt giới hạn bán hôm nay"),
				plainLine(LunaPalette.WARNING_500, "⏳ Reset " + service.tradeLimitResetTimeText())
			));
		}

		return item("hopper", "<yellow>★ Bán nhanh tất cả item tương tự", List.of(
			line(LunaPalette.WARNING_500, "♦ Có thể bán: <white>" + sellAmount),
			line(LunaPalette.WARNING_500, "💵 Dự kiến nhận: <gold>" + service.formatMoney(shopItem.sellPrice() * sellAmount)),
			"",
			actionLine("Chuột trái", "xác nhận bán toàn bộ")
		));
	}

	// ---------------------------------------------------------------- rendering helpers

	private void fillFooter(LunaChestMenuBase menu) {
		for (int slot = 45; slot <= 53; slot++) {
			menu.setDecoration(slot, item("gray_stained_glass_pane", "<gray> ", List.of()));
		}
	}

	private ItemStack nav(String material, String name) {
		return item(material, name, List.of());
	}

	private ItemStack item(String material, String title, List<String> loreLines) {
		return item(material, title, loreLines, 1);
	}

	private ItemStack item(String material, String title, List<String> loreLines, int count) {
		return LunaItems.of(material, title, wrapLore(loreLines), null, count);
	}

	/**
	 * An existing item turned into a button: its own lore first, then a blank
	 * line, then the shop's.
	 *
	 * A null title leaves the item's own name alone, which is what a shop entry
	 * wants - the player should recognise the sword, not read "Item #3f2a1c".
	 */
	private ItemStack decorate(ItemStack source, String title, List<String> shopLore) {
		ItemStack stack = source.copyWithCount(1);
		List<Component> lore = new ArrayList<>();
		ItemLore existing = stack.get(DataComponents.LORE);

		if (existing != null) {
			lore.addAll(existing.lines());
		}

		lore.add(Component.empty());

		for (String line : wrapLore(shopLore)) {
			lore.add(line.isEmpty() ? Component.empty() : LunaTextComponents.mini(line));
		}

		stack.set(DataComponents.LORE, new ItemLore(lore));

		if (title != null) {
			stack.set(DataComponents.CUSTOM_NAME, LunaTextComponents.mini(title));
		}

		return stack;
	}

	private List<String> wrapLore(List<String> loreLines) {
		List<String> wrapped = new ArrayList<>();

		for (String line : loreLines) {
			wrapped.addAll(LunaLore.wrapLoreLine(line));
		}

		return wrapped;
	}

	private void tell(ServerPlayer player, String miniMessage) {
		player.sendSystemMessage(LunaTextComponents.mini(miniMessage));
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

	private List<String> playerTradeLimitLore(ServerPlayer player, ShopItem item) {
		if (!item.hasBuyTradeLimit() && !item.hasSellTradeLimit()) {
			return List.of();
		}

		return List.of(
			coloredPairLine(LunaPalette.INFO_500, "⌚ Mua còn:", LunaPalette.SUCCESS_500, limitValueText(service.remainingBuyLimit(player, item), item.buyTradeLimit())),
			coloredPairLine(LunaPalette.INFO_500, "⌚ Bán còn:", LunaPalette.WARNING_500, limitValueText(service.remainingSellLimit(player, item), item.sellTradeLimit())),
			coloredPairLine(LunaPalette.INFO_300, "⏳ Reset:", LunaPalette.NEUTRAL_100, service.tradeLimitResetTimeText())
		);
	}

	private List<String> adminTradeLimitLore(ShopItem item) {
		if (!item.hasBuyTradeLimit() && !item.hasSellTradeLimit()) {
			return List.of();
		}

		return List.of(
			"",
			coloredPairLine(LunaPalette.INFO_500, "⌚ Hạn mức mua/ngày:", LunaPalette.SUCCESS_500, limitSettingText(item.buyTradeLimit())),
			coloredPairLine(LunaPalette.INFO_500, "⌚ Hạn mức bán/ngày:", LunaPalette.WARNING_500, limitSettingText(item.sellTradeLimit())),
			plainLine(LunaPalette.INFO_300, "⏳ Reset mỗi ngày Minecraft")
		);
	}

	private String limitValueText(int remaining, int maxLimit) {
		if (maxLimit <= 0) {
			return "Không giới hạn";
		}

		return Math.max(0, remaining) + "/" + maxLimit;
	}

	private String coloredPairLine(String labelColor, String label, String valueColor, String value) {
		return "<color:" + labelColor + ">" + label + "</color> <color:" + valueColor + ">" + value + "</color>";
	}

	private String limitSettingText(int maxLimit) {
		if (maxLimit <= 0) {
			return "Không giới hạn";
		}

		return String.valueOf(maxLimit);
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

	/** A blank, "none", "off" or "unlimited" all mean no limit. */
	public static int parseTradeLimitInput(String input) {
		if (input == null) {
			throw new IllegalArgumentException("Hạn mức phải là số nguyên >= 0 hoặc none.");
		}

		String normalized = input.trim().toLowerCase(Locale.ROOT);

		if (normalized.isBlank() || normalized.equals("none") || normalized.equals("off") || normalized.equals("unlimited")) {
			return 0;
		}

		int value;

		try {
			value = Integer.parseInt(normalized);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Hạn mức phải là số nguyên >= 0 hoặc none.", exception);
		}

		if (value < 0) {
			throw new IllegalArgumentException("Hạn mức phải là số nguyên >= 0 hoặc none.");
		}

		return value;
	}

	private static boolean isCancelWord(String input) {
		return input == null || input.isBlank() || input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel");
	}

	private Component breadcrumb(String... segments) {
		return LunaTextComponents.mini(String.join(
			"<color:" + LunaPalette.NEUTRAL_500 + "> › </color>",
			List.of(segments).stream().map(segment -> "<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">" + segment + "</color>").toList()
		));
	}

	private Component compactTitle(String text) {
		return LunaTextComponents.mini("<color:" + LunaPalette.GUI_TITLE_SECONDARY + ">" + text + "</color>");
	}

	private int maxPage(int items) {
		return LunaPagination.maxPage(items, PAGE_SIZE);
	}

	private int clampPage(int page, int maxPage) {
		return LunaPagination.clampPage(page, maxPage);
	}

	private int clampAmount(int amount) {
		return Math.max(1, Math.min(MAX_TRADE_AMOUNT, amount));
	}

	private String prettyCategory(String category) {
		if (category == null) {
			return "General";
		}

		StringBuilder builder = new StringBuilder();

		for (String part : category.split("-")) {
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

		List<ShopItem> sorted = new ArrayList<>(items);
		sorted.sort(comparator.thenComparing(ShopItem::id, String.CASE_INSENSITIVE_ORDER));
		return sorted;
	}

	private String itemSortName(ShopItem item) {
		return store.displayNameOf(item.itemStack(server));
	}

	private SortField nextSortField(SortField sortField) {
		SortField[] values = SortField.values();
		return values[(sortField.ordinal() + 1) % values.length];
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

	private enum ItemEditField {
		ID,
		BUY_PRICE,
		SELL_PRICE,
		CATEGORY,
		BUY_LIMIT,
		SELL_LIMIT
	}

	private enum AdminPromptType {
		CREATE_CATEGORY,
		RENAME_CATEGORY
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

	private record TradeSession(String itemId, TradeMode mode, int amount, BrowseContext context) {
		TradeSession withAmount(int value) {
			return new TradeSession(itemId, mode, value, context);
		}

		TradeSession withMode(TradeMode value) {
			return new TradeSession(itemId, value, amount, context);
		}
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
}
