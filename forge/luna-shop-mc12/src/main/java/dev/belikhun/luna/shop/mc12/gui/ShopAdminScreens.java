package dev.belikhun.luna.shop.mc12.gui;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.core.mc12.ui.LegacyChatPrompts;
import dev.belikhun.luna.core.mc12.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc12.ui.LunaItems;
import dev.belikhun.luna.core.mc12.ui.LunaMenuHost;
import dev.belikhun.luna.core.mc12.ui.NumberSelectorScreen;
import dev.belikhun.luna.legacy.shop.ShopCategory;
import dev.belikhun.luna.legacy.shop.ShopItem;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.shop.ShopItems;
import dev.belikhun.luna.legacy.shop.ShopService;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.ui.LunaGuiTitle;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The other half of the shop: the screens that fill it.
 *
 * Category management, the item list, the item editor and the create-item flow,
 * at the slots the Paper build uses, so an operator who knows one backend knows
 * this one. The verbs are the store's own - {@code upsert}, {@code renameCategory},
 * {@code deleteCategory} - so nothing about what a shop *is* is decided here.
 *
 * **Text comes from chat and numbers come from a chest.** 1.12.2 has no anvil
 * text input worth the trouble, so an id or a category name is typed into chat
 * through {@link LegacyChatPrompts}, and every number goes through
 * {@link NumberSelectorScreen} - which is also what the modern builds do, for the
 * same reason: a price is easier to nudge than to spell.
 *
 * Creating an item is a chain of those screens rather than one form, because a
 * chest cannot show a form. Each step carries the whole draft forward, so
 * cancelling half way leaves nothing behind.
 */
public final class ShopAdminScreens {
	private static final int PAGE_SIZE = 45;
	private static final int GUI_ROWS = 6;
	private static final int CONFIRM_ROWS = 3;

	private static final double MAX_PRICE = 1_000_000_000D;
	private static final double MAX_LIMIT = 1_000_000D;

	private final ShopService<EntityPlayerMP, ItemStack> service;
	private final ShopItemStore<ItemStack> store;
	private final ShopItems<ItemStack> items;
	private final ShopScreens playerScreens;
	private final LegacyChatPrompts chatPrompts;
	private final NumberSelectorScreen numbers;
	private final LunaMenuHost mainHost;
	private final LunaMenuHost confirmHost;

	public ShopAdminScreens(
		ShopService<EntityPlayerMP, ItemStack> service,
		ShopItemStore<ItemStack> store,
		ShopItems<ItemStack> items,
		ShopScreens playerScreens,
		LegacyChatPrompts chatPrompts
	) {
		this.service = service;
		this.store = store;
		this.items = items;
		this.playerScreens = playerScreens;
		this.chatPrompts = chatPrompts;
		this.numbers = new NumberSelectorScreen(chatPrompts);
		this.mainHost = new LunaMenuHost(GUI_ROWS);
		this.confirmHost = new LunaMenuHost(CONFIRM_ROWS);
	}

	public void forget(UUID playerId) {
		mainHost.forget(playerId);
		confirmHost.forget(playerId);
		numbers.forget(playerId);
		chatPrompts.cancel(playerId);
	}

	public void close() {
		mainHost.closeAll();
		confirmHost.closeAll();
		numbers.closeAll();
	}

	// ----------------------------------------------------------------- the hub

	public void openManagementMenu(final EntityPlayerMP player) {
		mainHost.open(player, LunaTextComponents.mini(title("Quản Lý")), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			menu.setTopSlot(20, LunaItems.of("chest", "<gold>♦ Quản Lý Danh Mục", Arrays.asList(
				"<gray>● Quản lý category và icon đại diện.",
				"",
				actionLine("Chuột trái", "mở")
			)), () -> openCategoryManagement(player, 0));

			menu.setTopSlot(24, LunaItems.of("book", "<aqua>♦ Quản Lý Mặt Hàng", Arrays.asList(
				"<gray>● Quản lý item shop, giá và danh mục.",
				"",
				actionLine("Chuột trái", "mở")
			)), () -> openItemManagement(player, 0));

			menu.setTopSlot(49, nav("emerald", "<green>★ Mở Shop người chơi"), () -> playerScreens.openMainMenu(player, 0));
			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), () -> mainHost.close(player));
		});
	}

	// ---------------------------------------------------------- the categories

	public void openCategoryManagement(final EntityPlayerMP player, int page) {
		final List<ShopCategory> categories = store.allCategories();
		final int maxPage = maxPage(categories.size());
		final int currentPage = clampPage(page, maxPage);

		mainHost.open(player, LunaTextComponents.mini(title("Quản Lý", "Danh Mục")), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(categories.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index += 1) {
				final ShopCategory category = categories.get(index);
				List<String> lore = Arrays.asList(
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
					LunaItems.decorate(category.iconItem(items, fallbackIcon()), "<gold>♦ </gold>" + displayCategory(category.id()), lore, 1),
					click -> {
						if (click.isShift() && click.isRight()) {
							deleteCategory(player, category.id(), currentPage);

							return;
						}

						if (click.isRight()) {
							promptRenameCategory(player, category.id(), currentPage);

							return;
						}

						setCategoryIcon(player, category.id(), currentPage);
					}
				);
			}

			fillFooter(menu);

			if (currentPage > 0) {
				menu.setTopSlot(45, nav("arrow", "<yellow>← Trang trước"), () -> openCategoryManagement(player, currentPage - 1));
			}

			if (currentPage < maxPage) {
				menu.setTopSlot(53, nav("arrow", "<yellow>Trang sau →"), () -> openCategoryManagement(player, currentPage + 1));
			}

			menu.setTopSlot(49, nav("anvil", "<aqua>➕ Tạo danh mục mới"), () -> promptCreateCategory(player, currentPage));
			menu.setTopSlot(50, nav("book", "<yellow>Quản lý mặt hàng"), () -> openItemManagement(player, 0));
			menu.setTopSlot(52, nav("arrow", "<yellow>← Quay lại"), () -> openManagementMenu(player));
		});
	}

	private void deleteCategory(EntityPlayerMP player, String categoryId, int page) {
		// the store refuses a category that still holds items rather than orphaning
		// them, so this is a report of its answer, not a check of our own
		if (!store.deleteCategory(categoryId, null)) {
			tell(player, "<red>❌ Danh mục còn item, không thể xóa trực tiếp.</red>");

			return;
		}

		store.save();
		tell(player, "<green>✔ Đã xóa danh mục <white>" + categoryId + "</white>.</green>");
		openCategoryManagement(player, page);
	}

	private void setCategoryIcon(EntityPlayerMP player, String categoryId, int page) {
		ItemStack hand = player.getHeldItemMainhand();

		if (items.isEmpty(hand)) {
			tell(player, "<red>❌ Hãy cầm item trên tay để đặt icon danh mục.</red>");

			return;
		}

		store.upsertCategoryIcon(categoryId, hand);
		store.save();
		tell(player, "<green>✔ Đã cập nhật icon cho danh mục <white>" + categoryId + "</white>.</green>");
		openCategoryManagement(player, page);
	}

	private void promptRenameCategory(final EntityPlayerMP player, final String categoryId, final int page) {
		ask(player, "<aqua>Nhập tên hiển thị mới cho <white>" + categoryId + "</white>", answer -> {
			if (isCancelWord(answer)) {
				openCategoryManagement(player, page);

				return;
			}

			if (!store.updateCategoryDisplayName(categoryId, answer)) {
				tell(player, "<red>❌ Không cập nhật được tên danh mục.</red>");
				openCategoryManagement(player, page);

				return;
			}

			store.save();
			tell(player, "<green>✔ Đã đổi tên danh mục <white>" + categoryId + "</white> thành <white>" + answer + "</white>.</green>");
			openCategoryManagement(player, page);
		});
	}

	private void promptCreateCategory(final EntityPlayerMP player, final int page) {
		ask(player, "<aqua>Nhập id cho danh mục mới <gray>(không dấu, không khoảng trắng)", answer -> {
			if (isCancelWord(answer)) {
				openCategoryManagement(player, page);

				return;
			}

			String id = answer.trim().toLowerCase(Locale.ROOT);

			if (store.findCategory(id).isPresent()) {
				tell(player, "<red>❌ Danh mục <white>" + id + "</white> đã tồn tại.</red>");
				openCategoryManagement(player, page);

				return;
			}

			store.upsertCategory(ShopCategory.defaultCategory(id));

			ItemStack hand = player.getHeldItemMainhand();

			// the icon is optional at creation: an empty hand simply leaves the
			// category drawing its fallback until someone sets one
			if (!items.isEmpty(hand)) {
				store.upsertCategoryIcon(id, hand);
			}

			store.save();
			tell(player, "<green>✔ Đã tạo danh mục <white>" + id + "</white>.</green>");
			openCategoryManagement(player, page);
		});
	}

	// --------------------------------------------------------------- the items

	public void openItemManagement(final EntityPlayerMP player, int page) {
		final List<ShopItem> all = store.all();
		final int maxPage = maxPage(all.size());
		final int currentPage = clampPage(page, maxPage);

		mainHost.open(player, LunaTextComponents.mini(title("Quản Lý", "Mặt Hàng")), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(all.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index += 1) {
				final ShopItem shopItem = all.get(index);
				List<String> lore = new ArrayList<String>();

				lore.add("<gray>№ <white>" + shopItem.id());
				lore.add("<gray>♦ Danh mục: " + displayCategory(shopItem.category()));
				lore.add("<green>Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()));
				lore.add("<yellow>Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice()));
				lore.addAll(limitLore(shopItem));
				lore.add("");
				lore.add(actionLine("Chuột trái", "mở trình chỉnh sửa"));

				menu.setTopSlot(
					index - start,
					LunaItems.decorate(shopItem.itemStack(items), null, lore, 1),
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

			menu.setTopSlot(49, nav("anvil", "<aqua>➕ Tạo item mới"), () -> beginCreateItem(player, currentPage));
			menu.setTopSlot(50, nav("chest", "<yellow>Quản lý danh mục"), () -> openCategoryManagement(player, 0));
			menu.setTopSlot(52, nav("arrow", "<yellow>← Quay lại"), () -> openManagementMenu(player));
		});
	}

	private void openItemEditor(final EntityPlayerMP player, final String itemId, final int page) {
		final ShopItem shopItem = store.find(itemId).orElse(null);

		if (shopItem == null) {
			tell(player, "<red>❌ Item không tồn tại.</red>");
			openItemManagement(player, page);

			return;
		}

		mainHost.open(player, LunaTextComponents.mini(title("Quản Lý", "Sửa Mặt Hàng")), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			List<String> preview = new ArrayList<String>();

			preview.add("<gray>№ <white>" + shopItem.id());
			preview.add("<gray>♦ Danh mục: " + displayCategory(shopItem.category()));
			preview.add("<green>Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()));
			preview.add("<yellow>Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice()));
			preview.addAll(limitLore(shopItem));

			menu.setDecoration(13, LunaItems.decorate(shopItem.itemStack(items), null, preview, 1));

			menu.setTopSlot(28, LunaItems.of("name_tag", "<aqua>✎ Sửa ID", Arrays.asList(
				"<gray>Hiện tại: <white>" + shopItem.id() + "</white>",
				actionLine("Chuột trái", "nhập ID mới bằng chat")
			)), () -> promptItemId(player, shopItem, page));

			menu.setTopSlot(29, LunaItems.of("emerald", "<green>Sửa giá mua", Arrays.asList(
				"<gray>Hiện tại: <gold>" + service.formatMoney(shopItem.buyPrice()) + "</gold>",
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> editPrice(player, shopItem, page, true));

			menu.setTopSlot(30, LunaItems.of("gold_ingot", "<yellow>Sửa giá bán", Arrays.asList(
				"<gray>Hiện tại: <gold>" + service.formatMoney(shopItem.sellPrice()) + "</gold>",
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> editPrice(player, shopItem, page, false));

			menu.setTopSlot(31, LunaItems.of("chest", "<aqua>♦ Sửa danh mục", Arrays.asList(
				"<gray>Hiện tại: " + displayCategory(shopItem.category()),
				actionLine("Chuột trái", "nhập category bằng chat")
			)), () -> promptItemCategory(player, shopItem, page));

			menu.setTopSlot(32, LunaItems.of("lime_dye", "<green>⌚ Hạn mức mua", Arrays.asList(
				"<gray>Hiện tại: <white>" + limitText(shopItem.buyTradeLimit()) + "</white>",
				"<gray>Đặt <white>0</white> để bỏ giới hạn",
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> editLimit(player, shopItem, page, true));

			menu.setTopSlot(33, LunaItems.of("orange_dye", "<yellow>⌚ Hạn mức bán", Arrays.asList(
				"<gray>Hiện tại: <white>" + limitText(shopItem.sellTradeLimit()) + "</white>",
				"<gray>Đặt <white>0</white> để bỏ giới hạn",
				actionLine("Chuột trái", "mở bộ chọn số")
			)), () -> editLimit(player, shopItem, page, false));

			menu.setTopSlot(34, LunaItems.of("anvil", "<aqua>Cập nhật vật phẩm", Arrays.asList(
				"<gray>Lấy item đang cầm làm vật phẩm mới",
				"<gray>Giá và hạn mức giữ nguyên",
				actionLine("Chuột trái", "cập nhật ngay")
			)), () -> replaceItemStack(player, shopItem, page));

			menu.setTopSlot(39, LunaItems.of("barrier", "<red>❌ Xóa mặt hàng", Arrays.asList(
				"<red>Hành động không thể hoàn tác",
				actionLine("Chuột trái", "xác nhận xóa")
			)), () -> confirmDeleteItem(player, shopItem, page));

			menu.setTopSlot(49, nav("arrow", "<yellow>← Quay lại danh sách"), () -> openItemManagement(player, page));
			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), () -> mainHost.close(player));
		});
	}

	private void promptItemId(final EntityPlayerMP player, final ShopItem shopItem, final int page) {
		ask(player, "<aqua>Nhập ID mới <gray>(hiện tại: <white>" + shopItem.id() + "</white>)", answer -> {
			if (isCancelWord(answer)) {
				openItemEditor(player, shopItem.id(), page);

				return;
			}

			String id = answer.trim();

			if (store.find(id).isPresent()) {
				tell(player, "<red>❌ Đã có mặt hàng mang ID <white>" + id + "</white>.</red>");
				openItemEditor(player, shopItem.id(), page);

				return;
			}

			// an id is the key, so this is a move rather than an edit: the old entry
			// has to go or the shop would hold the item twice
			store.remove(shopItem.id());
			store.upsert(shopItem.withId(id));
			store.save();
			tell(player, "<green>✔ Đã đổi ID thành <white>" + id + "</white>.</green>");
			openItemEditor(player, id, page);
		});
	}

	private void promptItemCategory(final EntityPlayerMP player, final ShopItem shopItem, final int page) {
		ask(player, "<aqua>Nhập category <gray>(hiện tại: <white>" + shopItem.category() + "</white>)", answer -> {
			if (isCancelWord(answer)) {
				openItemEditor(player, shopItem.id(), page);

				return;
			}

			String category = answer.trim().toLowerCase(Locale.ROOT);

			if (!store.findCategory(category).isPresent()) {
				store.upsertCategory(ShopCategory.defaultCategory(category));
			}

			store.upsert(shopItem.withCategory(category));
			store.save();
			tell(player, "<green>✔ Đã chuyển mặt hàng sang danh mục <white>" + category + "</white>.</green>");
			openItemEditor(player, shopItem.id(), page);
		});
	}

	private void editPrice(EntityPlayerMP player, final ShopItem shopItem, final int page, final boolean buying) {
		NumberSelectorScreen.Request request = NumberSelectorScreen.Request
			.of(
				title("Quản Lý", "Sửa Mặt Hàng", buying ? "Giá Mua" : "Giá Bán"),
				buying ? "Giá mua" : "Giá bán",
				(submitPlayer, value) -> {
					double price = Math.max(0D, value.doubleValue());

					store.upsert(buying ? shopItem.withBuyPrice(price) : shopItem.withSellPrice(price));
					store.save();
					tell(submitPlayer, "<green>✔ Đã đặt " + (buying ? "giá mua" : "giá bán") + " thành <gold>"
						+ service.formatMoney(price) + "</gold>.</green>");
					openItemEditor(submitPlayer, shopItem.id(), page);
				},
				cancelPlayer -> openItemEditor(cancelPlayer, shopItem.id(), page)
			)
			.withDisplayMaterial(buying ? "emerald" : "gold_ingot")
			.withInitialValue(buying ? shopItem.buyPrice() : shopItem.sellPrice())
			.withRange(0D, MAX_PRICE)
			.withIntegerMode(false)
			.withFormatter(value -> service.formatMoney(value));

		numbers.open(player, request);
	}

	private void editLimit(EntityPlayerMP player, final ShopItem shopItem, final int page, final boolean buying) {
		NumberSelectorScreen.Request request = NumberSelectorScreen.Request
			.of(
				title("Quản Lý", "Sửa Mặt Hàng", buying ? "Hạn Mức Mua" : "Hạn Mức Bán"),
				buying ? "Hạn mức mua" : "Hạn mức bán",
				(submitPlayer, value) -> {
					int limit = (int) Math.max(0D, Math.rint(value.doubleValue()));

					store.upsert(buying ? shopItem.withBuyTradeLimit(limit) : shopItem.withSellTradeLimit(limit));
					store.save();
					tell(submitPlayer, "<green>✔ Đã đặt " + (buying ? "hạn mức mua" : "hạn mức bán")
						+ " thành <white>" + limitText(limit) + "</white>.</green>");
					openItemEditor(submitPlayer, shopItem.id(), page);
				},
				cancelPlayer -> openItemEditor(cancelPlayer, shopItem.id(), page)
			)
			.withDisplayMaterial(buying ? "lime_dye" : "orange_dye")
			.withInitialValue(buying ? shopItem.buyTradeLimit() : shopItem.sellTradeLimit())
			.withRange(0D, MAX_LIMIT)
			.withIntegerMode(true)
			.withUnit("lượt");

		numbers.open(player, request);
	}

	private void replaceItemStack(EntityPlayerMP player, ShopItem shopItem, int page) {
		ItemStack hand = player.getHeldItemMainhand();

		if (items.isEmpty(hand)) {
			tell(player, "<red>❌ Hãy cầm item trên tay để cập nhật.</red>");
			openItemEditor(player, shopItem.id(), page);

			return;
		}

		store.upsert(ShopItem.fromItemStack(
			items,
			shopItem.id(),
			shopItem.category(),
			shopItem.buyPrice(),
			shopItem.sellPrice(),
			shopItem.buyTradeLimit(),
			shopItem.sellTradeLimit(),
			hand,
			shopItem.addedDate()
		));
		store.save();
		tell(player, "<green>✔ Đã cập nhật vật phẩm cho <white>" + shopItem.id() + "</white>.</green>");
		openItemEditor(player, shopItem.id(), page);
	}

	private void confirmDeleteItem(final EntityPlayerMP player, final ShopItem shopItem, final int page) {
		openConfirmationDialog(
			player,
			"<red>⚠ Xác nhận xóa mặt hàng",
			Arrays.asList(
				"<gray>Bạn sắp xóa item shop:",
				"<white>" + shopItem.id(),
				"",
				"<red>Hành động này không thể hoàn tác."
			),
			() -> {
				store.remove(shopItem.id());
				store.save();
				tell(player, "<green>✔ Đã xóa item <white>" + shopItem.id() + "</white> khỏi shop.</green>");
				openItemManagement(player, page);
			},
			() -> openItemEditor(player, shopItem.id(), page)
		);
	}

	// ------------------------------------------------------- creating an item

	/**
	 * Hand → category → buy price → sell price → buy limit → sell limit → item.
	 *
	 * The draft is carried through the chain rather than parked in a map keyed by
	 * player: a half-finished item that only exists inside the callbacks cannot be
	 * left behind by a disconnect, and there is nothing to clean up on the way out.
	 */
	private void beginCreateItem(final EntityPlayerMP player, final int page) {
		final ItemStack hand = player.getHeldItemMainhand();

		if (items.isEmpty(hand)) {
			tell(player, "<red>❌ Hãy cầm item trên tay để tạo mặt hàng.</red>");
			openItemManagement(player, page);

			return;
		}

		final ItemStack sample = hand.copy();

		ask(player, "<aqua>Nhập <white>category</white> cho item mới", answer -> {
			if (isCancelWord(answer)) {
				tell(player, "<yellow>ℹ Đã hủy tạo item.</yellow>");
				openItemManagement(player, page);

				return;
			}

			askBuyPrice(player, page, sample, answer.trim().toLowerCase(Locale.ROOT));
		});
	}

	private void askBuyPrice(EntityPlayerMP player, final int page, final ItemStack sample, final String category) {
		numbers.open(player, NumberSelectorScreen.Request
			.of(
				title("Quản Lý", "Tạo Item", "Giá Mua"),
				"Giá mua",
				(submitPlayer, value) -> askSellPrice(submitPlayer, page, sample, category, Math.max(0D, value.doubleValue())),
				cancelPlayer -> cancelCreate(cancelPlayer, page)
			)
			.withDisplayMaterial("emerald")
			.withRange(0D, MAX_PRICE)
			.withIntegerMode(false)
			.withFormatter(value -> service.formatMoney(value)));
	}

	private void askSellPrice(EntityPlayerMP player, final int page, final ItemStack sample, final String category, final double buyPrice) {
		numbers.open(player, NumberSelectorScreen.Request
			.of(
				title("Quản Lý", "Tạo Item", "Giá Bán"),
				"Giá bán",
				(submitPlayer, value) -> askBuyLimit(submitPlayer, page, sample, category, buyPrice, Math.max(0D, value.doubleValue())),
				cancelPlayer -> cancelCreate(cancelPlayer, page)
			)
			.withDisplayMaterial("gold_ingot")
			.withRange(0D, MAX_PRICE)
			.withIntegerMode(false)
			.withFormatter(value -> service.formatMoney(value)));
	}

	private void askBuyLimit(
		EntityPlayerMP player,
		final int page,
		final ItemStack sample,
		final String category,
		final double buyPrice,
		final double sellPrice
	) {
		numbers.open(player, NumberSelectorScreen.Request
			.of(
				title("Quản Lý", "Tạo Item", "Hạn Mức Mua"),
				"Hạn mức mua",
				(submitPlayer, value) -> askSellLimit(
					submitPlayer,
					page,
					sample,
					category,
					buyPrice,
					sellPrice,
					(int) Math.max(0D, Math.rint(value.doubleValue()))
				),
				cancelPlayer -> cancelCreate(cancelPlayer, page)
			)
			.withDisplayMaterial("lime_dye")
			.withRange(0D, MAX_LIMIT)
			.withIntegerMode(true)
			.withUnit("lượt"));
	}

	private void askSellLimit(
		EntityPlayerMP player,
		final int page,
		final ItemStack sample,
		final String category,
		final double buyPrice,
		final double sellPrice,
		final int buyLimit
	) {
		numbers.open(player, NumberSelectorScreen.Request
			.of(
				title("Quản Lý", "Tạo Item", "Hạn Mức Bán"),
				"Hạn mức bán",
				(submitPlayer, value) -> finishCreateItem(
					submitPlayer,
					page,
					sample,
					category,
					buyPrice,
					sellPrice,
					buyLimit,
					(int) Math.max(0D, Math.rint(value.doubleValue()))
				),
				cancelPlayer -> cancelCreate(cancelPlayer, page)
			)
			.withDisplayMaterial("orange_dye")
			.withRange(0D, MAX_LIMIT)
			.withIntegerMode(true)
			.withUnit("lượt"));
	}

	private void finishCreateItem(
		EntityPlayerMP player,
		int page,
		ItemStack sample,
		String category,
		double buyPrice,
		double sellPrice,
		int buyLimit,
		int sellLimit
	) {
		if (!store.findCategory(category).isPresent()) {
			store.upsertCategory(ShopCategory.defaultCategory(category));
			store.upsertCategoryIcon(category, sample);
		}

		ShopItem created = ShopItem.fromItemStackAutoId(items, category, buyPrice, sellPrice, buyLimit, sellLimit, sample);

		// the id is a hash of the item, so adding the same thing twice edits the
		// first one instead of making a second entry nobody could tell apart
		boolean replaced = store.find(created.id()).isPresent();

		store.upsert(created);
		store.save();

		tell(player, replaced
			? "<yellow>⚠ Item này đã có trong shop (<white>" + created.id() + "</white>); đã cập nhật lại giá.</yellow>"
			: "<green>✔ Đã thêm <white>" + items.displayName(sample) + "</white> vào danh mục <white>" + category
				+ "</white> (id <white>" + created.id() + "</white>).</green>");
		openItemEditor(player, created.id(), page);
	}

	private void cancelCreate(EntityPlayerMP player, int page) {
		tell(player, "<yellow>ℹ Đã hủy tạo item.</yellow>");
		openItemManagement(player, page);
	}

	// --------------------------------------------------------------- plumbing

	private void openConfirmationDialog(
		final EntityPlayerMP player,
		final String dialogTitle,
		final List<String> lines,
		final Runnable onConfirm,
		final Runnable onCancel
	) {
		confirmHost.open(player, LunaTextComponents.mini(title(dialogTitle)), menu -> {
			menu.clearTopSlots();

			for (int slot = 0; slot < CONFIRM_ROWS * 9; slot += 1) {
				menu.setDecoration(slot, LunaItems.of("gray_stained_glass_pane", "<gray> ", Collections.<String>emptyList()));
			}

			menu.setDecoration(13, LunaItems.of("paper", dialogTitle, lines));

			menu.setTopSlot(11, LunaItems.of("lime_dye", "<green>✔ Xác nhận", Collections.singletonList(
				actionLine("Chuột trái", "thực hiện")
			)), () -> {
				confirmHost.close(player);
				onConfirm.run();
			});

			menu.setTopSlot(15, LunaItems.of("red_dye", "<red>❌ Huỷ", Collections.singletonList(
				actionLine("Chuột trái", "quay lại")
			)), () -> {
				confirmHost.close(player);
				onCancel.run();
			});
		});
	}

	/**
	 * Close the screen and take the player's next chat line as the answer.
	 *
	 * The window has to go first: a player cannot type while a container is open,
	 * and leaving it up would look like the prompt was ignored.
	 */
	private void ask(EntityPlayerMP player, String question, java.util.function.Consumer<String> answer) {
		mainHost.close(player);
		tell(player, question);
		tell(player, "<aqua>✦ Nhập trên chat. Gõ <white>huy</white> để hủy.</aqua>");
		chatPrompts.await(player, answer);
	}

	private static boolean isCancelWord(String input) {
		return Strings.isBlank(input) || "huy".equalsIgnoreCase(input.trim()) || "cancel".equalsIgnoreCase(input.trim());
	}

	private List<String> limitLore(ShopItem shopItem) {
		return Arrays.asList(
			"<gray>Hạn mức mua: <white>" + limitText(shopItem.buyTradeLimit()),
			"<gray>Hạn mức bán: <white>" + limitText(shopItem.sellTradeLimit())
		);
	}

	private String limitText(int limit) {
		return limit <= 0 ? "không giới hạn" : String.valueOf(limit);
	}

	private ItemStack fallbackIcon() {
		return LunaItems.of("chest", "<gold>♦", Collections.<String>emptyList());
	}

	private void fillFooter(LunaChestMenu menu) {
		for (int slot = 45; slot <= 53; slot += 1) {
			menu.setDecoration(slot, LunaItems.of("black_stained_glass_pane", "<dark_gray> ", Collections.<String>emptyList()));
		}
	}

	private ItemStack nav(String material, String label) {
		return LunaItems.of(material, label, Collections.<String>emptyList());
	}

	private String actionLine(String button, String what) {
		return "<gray>▶ <aqua>" + button + "<gray>: " + what;
	}

	private String title(String... crumbs) {
		String[] full = new String[crumbs.length + 1];

		full[0] = "Luna Shop";
		System.arraycopy(crumbs, 0, full, 1, crumbs.length);

		return LunaGuiTitle.breadcrumb(full);
	}

	private String displayCategory(String category) {
		if (Strings.isBlank(category)) {
			return "Tất cả";
		}

		ShopCategory found = store.findCategory(category).orElse(null);

		if (found != null && found.hasDisplayName()) {
			return found.displayName();
		}

		String trimmed = category.trim();

		return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
	}

	private void tell(EntityPlayerMP player, String message) {
		player.sendMessage(LunaTextComponents.mini(message));
	}

	private int maxPage(int total) {
		return total <= 0 ? 0 : (total - 1) / PAGE_SIZE;
	}

	private int clampPage(int page, int maxPage) {
		return Math.max(0, Math.min(page, maxPage));
	}
}
