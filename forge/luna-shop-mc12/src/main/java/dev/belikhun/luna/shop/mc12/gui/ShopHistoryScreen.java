package dev.belikhun.luna.shop.mc12.gui;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.core.mc12.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc12.ui.LunaItems;
import dev.belikhun.luna.core.mc12.ui.LunaMenuHost;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.shop.ShopCategory;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.shop.ShopService;
import dev.belikhun.luna.legacy.shop.ShopTransactionEntry;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.ui.LunaGuiTitle;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What a player bought and sold, a page at a time.
 *
 * The same screen every other backend draws, down to the slots - 45 entries, the
 * arrows at 45 and 53, the info page at 50, the back button at 49 and the door at
 * 52 - because a player moving between a 1.12.2 and a modern backend should not
 * have to relearn where anything is.
 *
 * **The history lives in the database, not in the shop.** With no database
 * configured the screen still opens and says so, rather than being hidden: an
 * empty history and a disabled one look identical from the outside, and telling
 * them apart is the whole reason an operator opens this.
 */
public final class ShopHistoryScreen {
	private static final int ENTRY_CAPACITY = 45;
	private static final int SLOT_NOTICE = 22;
	private static final int SLOT_PREVIOUS_PAGE = 45;
	private static final int SLOT_BACK = 49;
	private static final int SLOT_INFO = 50;
	private static final int SLOT_CLOSE = 52;
	private static final int SLOT_NEXT_PAGE = 53;

	private static final String ITEM_SUCCESS = "lime_dye";
	private static final String ITEM_FAILURE = "red_dye";

	private final ShopService<EntityPlayerMP, ItemStack> service;
	private final ShopItemStore<ItemStack> store;
	private final PlayerBridge<EntityPlayerMP> players;
	private final LunaMenuHost menuHost;

	/** Where the back button goes; the shop's own main menu. */
	private final ShopScreens screens;

	public ShopHistoryScreen(
		ShopService<EntityPlayerMP, ItemStack> service,
		ShopItemStore<ItemStack> store,
		PlayerBridge<EntityPlayerMP> players,
		ShopScreens screens
	) {
		this.service = service;
		this.store = store;
		this.players = players;
		this.screens = screens;
		this.menuHost = new LunaMenuHost(6);
	}

	/** The viewer's own history. */
	public void open(EntityPlayerMP player, int page) {
		if (player == null) {
			return;
		}

		open(player, players.idOf(player), players.nameOf(player), page);
	}

	/**
	 * Someone else's history, for `/shopadmin history`.
	 *
	 * The target is passed as an id and a name rather than a player, because the
	 * point of the admin view is reading the history of someone who is not online.
	 */
	public void open(EntityPlayerMP viewer, final UUID targetId, final String targetName, final int page) {
		if (viewer == null || targetId == null) {
			return;
		}

		if (!service.isTransactionHistoryEnabled()) {
			renderDisabled(viewer);

			return;
		}

		final UUID viewerId = players.idOf(viewer);

		service.transactionHistoryPageAsync(targetId, page, ENTRY_CAPACITY).whenComplete((historyPage, throwable) ->
			players.onServerThread(() -> {
				// the query runs off the tick and the viewer may have left meanwhile
				EntityPlayerMP current = players.byId(viewerId);

				if (current == null) {
					return;
				}

				if (throwable != null || historyPage == null) {
					LunaTextComponents.send(current, "<red>❌ Không thể tải lịch sử giao dịch lúc này.</red>");

					return;
				}

				render(current, targetId, targetName, historyPage);
			}));
	}

	public void forget(UUID playerId) {
		menuHost.forget(playerId);
	}

	public void close() {
		menuHost.closeAll();
	}

	private void renderDisabled(final EntityPlayerMP player) {
		menuHost.open(player, LunaTextComponents.mini(title()), menu -> {
			menu.clearTopSlots();
			fillFooter(menu);

			menu.setDecoration(SLOT_NOTICE, LunaItems.of("barrier", "<red>❌ Database chưa bật</red>", Arrays.asList(
				"<gray>Lịch sử giao dịch cần một database.</gray>",
				"<gray>Bật khối <white>database</white> trong config của LunaCore.</gray>"
			)));

			drawExits(player, menu);
		});
	}

	private void render(
		final EntityPlayerMP viewer,
		final UUID targetId,
		final String targetName,
		final ShopService.ShopHistoryPage historyPage
	) {
		menuHost.open(viewer, LunaTextComponents.mini(title()), menu -> {
			menu.clearTopSlots();

			List<ShopTransactionEntry> entries = historyPage.entries();

			for (int index = 0; index < entries.size() && index < ENTRY_CAPACITY; index += 1) {
				ShopTransactionEntry entry = entries.get(index);

				menu.setDecoration(index, LunaItems.of(
					entry.success() ? ITEM_SUCCESS : ITEM_FAILURE,
					headlineFor(entry),
					loreFor(entry)
				));
			}

			if (entries.isEmpty()) {
				menu.setDecoration(SLOT_NOTICE, LunaItems.of("paper", "<yellow>ℹ Chưa có giao dịch</yellow>", Arrays.asList(
					"<gray>Chưa có giao dịch nào được ghi lại.</gray>"
				)));
			}

			fillFooter(menu);

			if (historyPage.currentPage() > 0) {
				menu.setTopSlot(
					SLOT_PREVIOUS_PAGE,
					nav("arrow", "<yellow>← Trang trước</yellow>"),
					() -> open(viewer, targetId, targetName, historyPage.currentPage() - 1)
				);
			}

			if (historyPage.currentPage() < historyPage.maxPage()) {
				menu.setTopSlot(
					SLOT_NEXT_PAGE,
					nav("arrow", "<yellow>Trang sau →</yellow>"),
					() -> open(viewer, targetId, targetName, historyPage.currentPage() + 1)
				);
			}

			menu.setDecoration(SLOT_INFO, LunaItems.of("book", "<aqua>ℹ Thông tin</aqua>", Arrays.asList(
				"<white>Người chơi: <yellow>" + safeName(targetName) + "</yellow></white>",
				"<white>Tổng giao dịch: <yellow>" + historyPage.total() + "</yellow></white>",
				"<white>Trang: <yellow>" + (historyPage.currentPage() + 1)
					+ "/" + (historyPage.maxPage() + 1) + "</yellow></white>"
			)));

			drawExits(viewer, menu);
		});
	}

	private void drawExits(final EntityPlayerMP player, LunaChestMenu menu) {
		menu.setTopSlot(
			SLOT_BACK,
			nav("chest", "<yellow>Danh mục chính</yellow>"),
			() -> screens.openMainMenu(player, 0)
		);

		menu.setTopSlot(
			SLOT_CLOSE,
			nav("oak_door", "<red>Đóng</red>"),
			() -> menuHost.close(player)
		);
	}

	/** `MUA #a1b2c3d4` - the id is trimmed to eight, which is enough to quote in a report. */
	private String headlineFor(ShopTransactionEntry entry) {
		boolean buy = isBuy(entry);
		String colour = buy ? "green" : "gold";
		String id = entry.transactionId() == null ? "" : entry.transactionId();

		return "<" + colour + ">" + (buy ? "MUA" : "BÁN") + "</" + colour + ">"
			+ " <white>#" + id.substring(0, Math.min(8, id.length())) + "</white>";
	}

	private boolean isBuy(ShopTransactionEntry entry) {
		return "BUY".equalsIgnoreCase(entry.action());
	}

	private List<String> loreFor(ShopTransactionEntry entry) {
		List<String> lore = new ArrayList<String>();

		lore.add("<white>Vật phẩm: <yellow>" + safeName(entry.itemId()) + "</yellow></white>");
		lore.add("<white>Danh mục: <aqua>" + displayCategory(entry.category()) + "</aqua></white>");
		lore.add("<white>Số lượng: <yellow>" + entry.amount() + "</yellow></white>");
		lore.add("<white>Đơn giá: <gold>" + service.formatMoney(entry.unitPrice()) + "</gold></white>");
		lore.add("<white>Tổng tiền: <gold>" + service.formatMoney(entry.totalPrice()) + "</gold></white>");
		lore.add("<white>Kết quả: " + (entry.success()
			? "<green>THÀNH CÔNG</green>"
			: "<red>THẤT BẠI</red>") + "</white>");
		lore.add("<white>Thời gian: <gray>" + Formatters.date(Instant.ofEpochMilli(entry.createdAt())) + "</gray></white>");

		if (!entry.success() && !Strings.isBlank(entry.reason())) {
			lore.add("<white>Lý do: <red>" + entry.reason() + "</red></white>");
		}

		return lore;
	}

	private String displayCategory(String category) {
		if (Strings.isBlank(category)) {
			return "Tất cả";
		}

		ShopCategory found = store.findCategory(category).orElse(null);

		if (found != null && found.hasDisplayName()) {
			return found.displayName();
		}

		return category.trim();
	}

	private void fillFooter(LunaChestMenu menu) {
		for (int slot = 45; slot <= 53; slot += 1) {
			menu.setDecoration(slot, LunaItems.of("black_stained_glass_pane", "<dark_gray> ", Collections.<String>emptyList()));
		}
	}

	private ItemStack nav(String material, String label) {
		return LunaItems.of(material, label, Collections.<String>emptyList());
	}

	private String title() {
		return LunaGuiTitle.breadcrumb("Luna Shop", "Lịch Sử");
	}

	private String safeName(String value) {
		return Strings.isBlank(value) ? "—" : value;
	}
}
