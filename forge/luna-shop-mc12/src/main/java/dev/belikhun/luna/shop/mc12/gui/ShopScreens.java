package dev.belikhun.luna.shop.mc12.gui;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.core.mc12.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc12.ui.LunaItems;
import dev.belikhun.luna.core.mc12.ui.LunaMenuHost;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.shop.ShopBrowse;
import dev.belikhun.luna.legacy.shop.ShopBrowse.Context;
import dev.belikhun.luna.legacy.shop.ShopBrowse.TradeMode;
import dev.belikhun.luna.legacy.shop.ShopBrowse.TradeSession;
import dev.belikhun.luna.legacy.shop.ShopCategory;
import dev.belikhun.luna.legacy.shop.ShopItem;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.shop.ShopItems;
import dev.belikhun.luna.legacy.shop.ShopResult;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shop a player sees on 1.12.2: categories, items, and the trade screen.
 *
 * The paper controller is the reference, down to the slot numbers - a player who
 * knows where the confirm button is on one backend finds it in the same place
 * here. What differs is only the plumbing: {@link LunaMenuHost} instead of
 * Bukkit's inventory holders, and every screen drawn fresh rather than mutated,
 * which is what makes "reopen the page I was on" the answer to every action.
 *
 * **Two buttons the modern build has are missing, and on purpose.** Searching and
 * typing an exact amount both go through the core's chat-prompt service, which
 * does not exist on this line yet; search is reachable as `/shop search <text>`
 * instead, and the quick amounts plus the adjust row cover the rest. They are not
 * drawn as dead buttons, because a button that does nothing is worse than no
 * button.
 */
public final class ShopScreens {
	private static final int PAGE_SIZE = 45;
	private static final int GUI_ROWS = 6;
	private static final int CONFIRM_ROWS = 3;
	private static final int MAX_TRADE_AMOUNT = 4096;

	private static final int[] QUICK_AMOUNT_SLOTS = {28, 29, 30, 32, 33, 34};
	private static final int[] QUICK_AMOUNTS = {1, 4, 8, 16, 32, 64};
	private static final int[] DECREASE_SLOTS = {36, 37, 38, 39};
	private static final int[] DECREASE_VALUES = {-8, -4, -2, -1};
	private static final int[] INCREASE_SLOTS = {41, 42, 43, 44};
	private static final int[] INCREASE_VALUES = {1, 2, 4, 8};
	private static final int CONFIRM_SLOT = 40;
	private static final int PREVIEW_SLOT = 13;
	private static final int QUICK_SELL_SLOT = 23;

	private final ShopService<EntityPlayerMP, ItemStack> service;
	private final ShopItemStore<ItemStack> store;
	private final ShopItems<ItemStack> items;
	private final PlayerBridge<EntityPlayerMP> players;
	private final LunaMenuHost mainHost;
	private final LunaMenuHost confirmHost;

	/** Last balance the wallet reported, per player; see knownBalanceText. */
	private final Map<UUID, Double> knownBalance = new ConcurrentHashMap<UUID, Double>();

	/**
	 * Set after construction, because the history screen's back button opens this
	 * one: the two refer to each other and something has to be built first.
	 */
	private ShopHistoryScreen history;

	public ShopScreens(
		ShopService<EntityPlayerMP, ItemStack> service,
		ShopItemStore<ItemStack> store,
		ShopItems<ItemStack> items,
		PlayerBridge<EntityPlayerMP> players
	) {
		this.service = service;
		this.store = store;
		this.items = items;
		this.players = players;
		this.mainHost = new LunaMenuHost(GUI_ROWS);
		this.confirmHost = new LunaMenuHost(CONFIRM_ROWS);
	}

	/** Give the main menu its history button. Null leaves the slot empty. */
	public void useHistory(ShopHistoryScreen history) {
		this.history = history;
	}

	public void forget(UUID playerId) {
		mainHost.forget(playerId);
		confirmHost.forget(playerId);
		knownBalance.remove(playerId);
	}

	/**
	 * The balance as last reported, without asking the wallet.
	 *
	 * A trade menu is redrawn on every amount click and the wallet lives on the
	 * proxy, so asking during a draw puts a network round trip inside a click
	 * handler. The number is decoration; a slightly stale one costs nothing and a
	 * stalled tick costs everyone.
	 */
	private String knownBalanceText(EntityPlayerMP player) {
		Double balance = knownBalance.get(players.idOf(player));

		return balance == null ? "—" : service.formatMoney(balance.doubleValue());
	}

	/**
	 * Ask the wallet, and redraw only if the answer is new.
	 *
	 * The "only if new" is what stops this looping: the redraw asks again, gets the
	 * same value, and settles.
	 */
	private void refreshKnownBalance(EntityPlayerMP player, final TradeSession session) {
		final UUID playerId = players.idOf(player);

		service.economy().balanceAsync(player).whenComplete((balance, failure) -> players.onServerThread(() -> {
			if (failure != null || balance == null) {
				return;
			}

			Double previous = knownBalance.put(playerId, balance);

			if (previous != null && previous.doubleValue() == balance.doubleValue()) {
				return;
			}

			EntityPlayerMP current = players.byId(playerId);

			if (current != null) {
				openTradeMenu(current, session);
			}
		}));
	}

	public void close() {
		mainHost.closeAll();
		confirmHost.closeAll();
	}

	// ------------------------------------------------------------- the browsing

	public void openMainMenu(final EntityPlayerMP player, int page) {
		final List<ShopCategory> categories = store.allCategories();
		final int maxPage = maxPage(categories.size());
		final int currentPage = clampPage(page, maxPage);

		mainHost.open(player, LunaTextComponents.mini(title("Cửa Hàng")), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(categories.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index += 1) {
				final ShopCategory category = categories.get(index);
				List<String> lore = Arrays.asList(
					"<gray>♦ Số mặt hàng: <green>" + store.byCategory(category.id()).size(),
					actionLine("Chuột trái", "mở danh mục này")
				);

				menu.setTopSlot(
					index - start,
					LunaItems.decorate(category.iconItem(items, fallbackIcon()), "<gold>♦ </gold>" + displayCategory(category.id()), lore, 1),
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

			if (history != null) {
				menu.setTopSlot(
					50,
					nav("book", "<yellow>⌚ Lịch sử giao dịch"),
					() -> history.open(player, 0)
				);
			}

			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), () -> mainHost.close(player));
		});
	}

	public void openCategoryMenu(EntityPlayerMP player, String category, int page) {
		openItemList(player, Context.category(category, page));
	}

	public void openSearchMenu(EntityPlayerMP player, String query, int page) {
		openItemList(player, Context.search(query, page));
	}

	private void openItemList(final EntityPlayerMP player, Context context) {
		List<ShopItem> found = itemsFor(context);
		final List<ShopItem> sorted = ShopBrowse.sort(found, context.sortField(), context.sortAscending(), this::nameOf);
		final int maxPage = maxPage(sorted.size());
		final int currentPage = clampPage(context.page(), maxPage);
		final Context pageContext = context.withPage(currentPage);

		mainHost.open(player, LunaTextComponents.mini(titleFor(context)), menu -> {
			menu.clearTopSlots();

			int start = currentPage * PAGE_SIZE;
			int end = Math.min(sorted.size(), start + PAGE_SIZE);

			for (int index = start; index < end; index += 1) {
				final ShopItem shopItem = sorted.get(index);
				List<String> lore = new ArrayList<String>();

				lore.add("<gray>№ <white>" + shopItem.id());
				lore.add("<gray>♦ Danh mục: " + displayCategory(shopItem.category()));
				lore.add("<green>Giá mua: <gold>" + service.formatMoney(shopItem.buyPrice()));
				lore.add("<yellow>Giá bán: <gold>" + service.formatMoney(shopItem.sellPrice()));
				lore.add("");
				lore.addAll(tradeLimitLore(player, shopItem));
				lore.add("");
				lore.add(actionLine("Chuột trái", "mua"));
				lore.add(actionLine("Chuột phải", "bán"));

				menu.setTopSlot(
					index - start,
					LunaItems.decorate(shopItem.itemStack(items), null, lore, 1),
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

			menu.setTopSlot(47, LunaItems.of("hopper", "<aqua>⚙ Sắp xếp theo", Arrays.asList(
				"<white>Tiêu chí hiện tại: <yellow>" + context.sortField().label(),
				actionLine("Chuột trái", "đổi tiêu chí")
			)), () -> openItemList(player, pageContext.withSortField(context.sortField().next())));

			menu.setTopSlot(48, LunaItems.of("comparator", "<aqua>⇅ Thứ tự", Arrays.asList(
				"<white>Hiện tại: <yellow>" + (context.sortAscending() ? "Tăng dần" : "Giảm dần"),
				actionLine("Chuột trái", "đảo thứ tự")
			)), () -> openItemList(player, pageContext.toggleSortDirection()));

			menu.setTopSlot(50, nav("chest", "<yellow>Danh mục chính"), () -> openMainMenu(player, 0));
			menu.setTopSlot(52, nav("oak_door", "<red>Đóng"), () -> mainHost.close(player));
		});
	}

	private List<ShopItem> itemsFor(Context context) {
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

	// -------------------------------------------------------------- the trading

	private void openTradeMenu(final EntityPlayerMP player, TradeSession session) {
		final ShopItem shopItem = store.find(session.itemId()).orElse(null);

		if (shopItem == null) {
			tell(player, "<red>❌ Không tìm thấy vật phẩm này trong shop.</red>");
			openMainMenu(player, 0);

			return;
		}

		final int amount = clampAmount(session.amount());
		final TradeSession normalized = session.withAmount(amount);

		mainHost.open(player, LunaTextComponents.mini(title("Giao Dịch: " + amount)), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			drawTradePreview(player, menu, shopItem, amount);

			menu.setTopSlot(20, modeButton(TradeMode.BUY, normalized.mode() == TradeMode.BUY),
				() -> openTradeMenu(player, normalized.withMode(TradeMode.BUY)));
			menu.setTopSlot(24, modeButton(TradeMode.SELL, normalized.mode() == TradeMode.SELL),
				() -> openTradeMenu(player, normalized.withMode(TradeMode.SELL)));

			for (int index = 0; index < QUICK_AMOUNTS.length; index += 1) {
				final int amountValue = QUICK_AMOUNTS[index];

				menu.setTopSlot(
					QUICK_AMOUNT_SLOTS[index],
					amountButton(shopItem, normalized.mode(), amountValue),
					() -> attemptTrade(player, shopItem, normalized.withAmount(amountValue))
				);
			}

			for (int index = 0; index < DECREASE_VALUES.length; index += 1) {
				final int delta = DECREASE_VALUES[index];

				menu.setTopSlot(DECREASE_SLOTS[index], adjustButton(delta),
					() -> openTradeMenu(player, normalized.withAmount(clampAmount(amount + delta))));
			}

			for (int index = 0; index < INCREASE_VALUES.length; index += 1) {
				final int delta = INCREASE_VALUES[index];

				menu.setTopSlot(INCREASE_SLOTS[index], adjustButton(delta),
					() -> openTradeMenu(player, normalized.withAmount(clampAmount(amount + delta))));
			}

			menu.setTopSlot(CONFIRM_SLOT, confirmButton(shopItem, normalized.mode(), amount),
				() -> attemptTrade(player, shopItem, normalized));

			menu.setTopSlot(45, LunaItems.of("arrow", "<yellow>← Quay lại", Arrays.asList(
				"<yellow>Quay về danh sách trước",
				"",
				"<white>Số lượng vừa chọn vẫn giữ"
			)), () -> openItemList(player, normalized.context()));

			drawQuickSellAll(player, menu, shopItem, normalized);

			menu.setTopSlot(52, LunaItems.of("oak_door", "<red>Đóng", Collections.singletonList(
				"<red>Thoát giao diện giao dịch"
			)), () -> mainHost.close(player));
		});

		// after the menu is on screen, never before: the point is that the draw does
		// not wait for the wallet
		refreshKnownBalance(player, normalized);
	}

	private void drawTradePreview(EntityPlayerMP player, LunaChestMenu menu, ShopItem shopItem, int amount) {
		int cappedBuy = service.capBuyAmount(player, shopItem, amount);
		int cappedSell = service.capSellAmount(player, shopItem, amount);

		List<String> lore = new ArrayList<String>();

		lore.add("<white>№ <yellow>" + shopItem.id());
		lore.add("<white>♦ Danh mục: " + displayCategory(shopItem.category()));
		lore.add("<white>♦ Số lượng: <yellow>" + amount);
		lore.add("<green>Tổng mua (áp dụng): <gold>" + service.formatMoney(shopItem.buyPrice() * cappedBuy));
		lore.add("<yellow>Tổng bán (áp dụng): <gold>" + service.formatMoney(shopItem.sellPrice() * cappedSell));
		lore.add("");
		lore.add("<white>Số dư hiện tại: <yellow>" + knownBalanceText(player));
		lore.add("");
		lore.addAll(tradeLimitLore(player, shopItem));

		menu.setDecoration(PREVIEW_SLOT, LunaItems.decorate(shopItem.itemStack(items), null, lore, amount));
	}

	private void drawQuickSellAll(final EntityPlayerMP player, LunaChestMenu menu, final ShopItem shopItem, final TradeSession session) {
		if (session.mode() != TradeMode.SELL) {
			menu.setDecoration(QUICK_SELL_SLOT, LunaItems.of("gray_dye", "<white>★ Bán nhanh tất cả", Arrays.asList(
				"<white>Chỉ dùng trong chế độ <yellow>Bán",
				"",
				"<white>Đổi mode để bán nhanh hơn"
			)));

			return;
		}

		int owned = service.countSimilar(player, shopItem.itemStack(items));

		menu.setTopSlot(QUICK_SELL_SLOT, quickSellAllButton(player, shopItem, owned), () -> {
			int sellAmount = service.countSimilar(player, shopItem.itemStack(items));

			if (sellAmount <= 0) {
				tell(player, "<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>");

				return;
			}

			final int effective = service.capSellAmount(player, shopItem, sellAmount);

			if (effective <= 0) {
				tellLimitReached(player, TradeMode.SELL);

				return;
			}

			openConfirmationDialog(
				player,
				"<yellow>⚠ Xác nhận bán nhanh toàn bộ",
				Arrays.asList(
					"<gray>Vật phẩm: <white>" + shopItem.id(),
					"<gray>Số lượng sẽ bán: <white>" + effective,
					"<gray>Tiền dự kiến nhận: <gold>" + service.formatMoney(shopItem.sellPrice() * effective)
				),
				() -> settle(player, session, service.sellAllSimilarAsync(player, shopItem)),
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
	private void attemptTrade(final EntityPlayerMP player, final ShopItem shopItem, final TradeSession session) {
		int effective = session.mode() == TradeMode.BUY
			? service.capBuyAmount(player, shopItem, session.amount())
			: service.capSellAmount(player, shopItem, session.amount());

		if (effective <= 0) {
			tellLimitReached(player, session.mode());

			return;
		}

		if (session.mode() == TradeMode.BUY) {
			// the balance is a proxy round trip on a cold cache, so the rest of the
			// buy path waits for it off the tick rather than in the click handler
			final double total = shopItem.buyPrice() * effective;
			final UUID playerId = players.idOf(player);

			service.economy().balanceAsync(player).whenComplete((balance, failure) -> players.onServerThread(() -> {
				EntityPlayerMP current = players.byId(playerId);

				if (current == null) {
					return;
				}

				confirmLargeBuy(current, shopItem, session, total, balance == null ? 0D : balance.doubleValue());
			}));

			return;
		}

		if (session.mode() == TradeMode.SELL && service.countSimilar(player, shopItem.itemStack(items)) < effective) {
			tell(player, "<yellow>⚠ Bạn không đủ vật phẩm tương ứng để bán.</yellow>");

			return;
		}

		completeTrade(player, shopItem, session);
	}

	/**
	 * Ask before a buy that would spend more than half of what the player has.
	 *
	 * That threshold is the one guard against a mis-click on a quick-amount button
	 * emptying an account, and it is deliberately about the share of the balance
	 * rather than an absolute price.
	 */
	private void confirmLargeBuy(
		EntityPlayerMP player,
		ShopItem shopItem,
		TradeSession session,
		double total,
		double balance
	) {
		if (balance < total) {
			tell(player, "<yellow>⚠ Bạn không đủ tiền để thực hiện giao dịch này.</yellow>");

			return;
		}

		if (balance > 0D && total > balance * 0.5D) {
			openConfirmationDialog(
				player,
				"<yellow>⚠ Xác nhận mua đơn lớn",
				Arrays.asList(
					"<gray>Tổng tiền: <gold>" + service.formatMoney(total),
					"<gray>Số dư hiện tại: <white>" + service.formatMoney(balance),
					"<gray>Lệnh mua này vượt <white>50%</white> số dư của bạn."
				),
				() -> completeTrade(player, shopItem, session),
				() -> openTradeMenu(player, session)
			);

			return;
		}

		completeTrade(player, shopItem, session);
	}

	private void completeTrade(EntityPlayerMP player, ShopItem shopItem, TradeSession session) {
		settle(player, session, session.mode() == TradeMode.BUY
			? service.buyAsync(player, shopItem, session.amount())
			: service.sellAsync(player, shopItem, session.amount()));
	}

	/**
	 * Report a trade once the wallet has answered, back on the server thread.
	 *
	 * The player is looked up again rather than captured, because the round trip
	 * outlives a disconnect and reopening a menu for someone who has left would put
	 * a container on a player object the server has already forgotten.
	 */
	private void settle(EntityPlayerMP player, final TradeSession session, CompletableFuture<ShopResult> pending) {
		final UUID playerId = players.idOf(player);

		pending.whenComplete((result, failure) -> players.onServerThread(() -> {
			EntityPlayerMP current = players.byId(playerId);

			if (current == null) {
				return;
			}

			if (failure != null || result == null) {
				tell(current, "<red>❌ Giao dịch không hoàn tất được. Vui lòng thử lại.</red>");
				openTradeMenu(current, session);

				return;
			}

			tell(current, result.message());
			openTradeMenu(current, session);
		}));
	}

	private void tellLimitReached(EntityPlayerMP player, TradeMode mode) {
		String modeText = mode == TradeMode.BUY ? "mua" : "bán";

		tell(player, "<yellow>⚠ Bạn đã đạt hạn mức " + modeText + " trong ngày. Reset <white>"
			+ service.tradeLimitResetTimeText() + "</white>.</yellow>");
	}

	// ---------------------------------------------------------- the confirmation

	private void openConfirmationDialog(
		final EntityPlayerMP player,
		final String title,
		final List<String> lines,
		final Runnable onConfirm,
		final Runnable onCancel
	) {
		confirmHost.open(player, LunaTextComponents.mini(title(title)), menu -> {
			menu.clearTopSlots();

			for (int slot = 0; slot < CONFIRM_ROWS * 9; slot += 1) {
				menu.setDecoration(slot, LunaItems.of("gray_stained_glass_pane", "<gray> ", Collections.<String>emptyList()));
			}

			menu.setDecoration(13, LunaItems.of("paper", title, lines));

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

	// ------------------------------------------------------------------ buttons

	private ItemStack confirmButton(ShopItem shopItem, TradeMode mode, int amount) {
		double total = mode == TradeMode.BUY ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;

		if (mode == TradeMode.BUY) {
			return LunaItems.of("emerald", "<green>✔ Xác nhận mua", Arrays.asList(
				"<green>♦ Số lượng: <white>" + amount,
				"<green>Tổng thanh toán: <gold>" + service.formatMoney(total),
				"",
				actionLine("Chuột trái", "mua ngay")
			), null, amount);
		}

		return LunaItems.of("gold_ingot", "<yellow>✔ Xác nhận bán", Arrays.asList(
			"<yellow>♦ Số lượng: <white>" + amount,
			"<yellow>Tổng nhận: <gold>" + service.formatMoney(total),
			"",
			actionLine("Chuột trái", "bán ngay")
		), null, amount);
	}

	private ItemStack modeButton(TradeMode mode, boolean active) {
		if (mode == TradeMode.BUY) {
			return LunaItems.of(
				active ? "lime_dye" : "gray_dye",
				active ? "<green>▶ Chế độ Mua" : "<white>Chuyển sang Mua",
				Arrays.asList(
					active ? "<green>Đang ở chế độ mua vật phẩm" : actionLine("Chuột trái", "đổi sang mua"),
					"",
					"<white>Mua theo số lượng bên dưới"
				)
			);
		}

		return LunaItems.of(
			active ? "orange_dye" : "gray_dye",
			active ? "<yellow>▶ Chế độ Bán" : "<white>Chuyển sang Bán",
			Arrays.asList(
				active ? "<yellow>Đang ở chế độ bán vật phẩm" : actionLine("Chuột trái", "đổi sang bán"),
				"",
				"<white>Bán theo số lượng bên dưới"
			)
		);
	}

	private ItemStack amountButton(ShopItem shopItem, TradeMode mode, int amount) {
		boolean buy = mode == TradeMode.BUY;
		double total = buy ? shopItem.buyPrice() * amount : shopItem.sellPrice() * amount;
		String colour = buy ? "<green>" : "<yellow>";

		return LunaItems.of("paper", (buy ? "<aqua>Mua nhanh <white>" : "<yellow>Bán nhanh <white>") + amount, Arrays.asList(
			colour + "♦ Số lượng: <white>" + amount,
			colour + (buy ? "Tổng thanh toán: <gold>" : "Tổng nhận: <gold>") + service.formatMoney(total),
			"",
			actionLine("Chuột trái", buy ? "mua ngay" : "bán ngay")
		), null, amount);
	}

	private ItemStack adjustButton(int delta) {
		String material = delta > 0 ? "lime_stained_glass_pane" : "red_stained_glass_pane";
		String modeText = delta > 0 ? "Tăng" : "Giảm";
		String prefix = delta > 0 ? "+" : "";

		return LunaItems.of(material, "<white>" + prefix + delta, Arrays.asList(
			(delta > 0 ? "<green>" : "<red>") + "♦ " + modeText + " thêm <white>" + Math.abs(delta),
			"",
			"<gray>Nhấn nhiều lần để chỉnh nhanh hơn"
		), null, Math.abs(delta));
	}

	private ItemStack quickSellAllButton(EntityPlayerMP player, ShopItem shopItem, int ownedAmount) {
		int sellAmount = service.capSellAmount(player, shopItem, Math.max(0, ownedAmount));

		if (ownedAmount <= 0) {
			return LunaItems.of("hopper", "<yellow>★ Bán nhanh tất cả item tương tự", Arrays.asList(
				"<yellow>♦ Có thể bán: <white>0",
				"",
				"<yellow>⚠ Bạn chưa có item tương tự trong túi đồ"
			));
		}

		if (sellAmount <= 0) {
			return LunaItems.of("hopper", "<yellow>★ Bán nhanh tất cả item tương tự", Arrays.asList(
				"<yellow>♦ Có thể bán: <white>0",
				"",
				"<yellow>⚠ Đã đạt giới hạn bán hôm nay",
				"<yellow>⏳ Reset " + service.tradeLimitResetTimeText()
			));
		}

		return LunaItems.of("hopper", "<yellow>★ Bán nhanh tất cả item tương tự", Arrays.asList(
			"<yellow>♦ Có thể bán: <white>" + sellAmount,
			"<yellow>Dự kiến nhận: <gold>" + service.formatMoney(shopItem.sellPrice() * sellAmount),
			"",
			actionLine("Chuột trái", "bán tất cả")
		));
	}

	// ----------------------------------------------------------------- plumbing

	/** The item a category with no readable icon is drawn as. */
	private ItemStack fallbackIcon() {
		return LunaItems.of("chest", "<gold>♦", Collections.<String>emptyList());
	}

	private List<String> tradeLimitLore(EntityPlayerMP player, ShopItem shopItem) {
		List<String> lore = new ArrayList<String>();

		if (shopItem.hasBuyTradeLimit()) {
			lore.add("<gray>Hạn mức mua hôm nay: <white>" + service.remainingBuyLimit(player, shopItem)
				+ "<gray>/<white>" + shopItem.buyTradeLimit());
		}

		if (shopItem.hasSellTradeLimit()) {
			lore.add("<gray>Hạn mức bán hôm nay: <white>" + service.remainingSellLimit(player, shopItem)
				+ "<gray>/<white>" + shopItem.sellTradeLimit());
		}

		if (lore.isEmpty()) {
			lore.add("<gray>Không giới hạn giao dịch");
		}

		return lore;
	}

	private String nameOf(ShopItem shopItem) {
		String name = items.displayName(shopItem.itemStack(items));

		return Strings.isBlank(name) ? shopItem.id() : name;
	}

	private void fillFooter(LunaChestMenu menu) {
		for (int slot = 45; slot <= 53; slot += 1) {
			menu.setDecoration(slot, LunaItems.of("black_stained_glass_pane", "<dark_gray> ", Collections.<String>emptyList()));
		}
	}

	private ItemStack nav(String material, String title) {
		return LunaItems.of(material, title, Collections.<String>emptyList());
	}

	private String actionLine(String button, String what) {
		return "<gray>▶ <aqua>" + button + "<gray>: " + what;
	}

	private String title(String text) {
		return LunaGuiTitle.breadcrumb("Luna Shop", text);
	}

	private String titleFor(Context context) {
		if (context.search()) {
			return title("Tìm kiếm: " + (context.query() == null ? "" : context.query()));
		}

		return title("Danh mục: " + displayCategory(context.category()));
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

	private int clampAmount(int amount) {
		return Math.max(1, Math.min(MAX_TRADE_AMOUNT, amount));
	}
}
