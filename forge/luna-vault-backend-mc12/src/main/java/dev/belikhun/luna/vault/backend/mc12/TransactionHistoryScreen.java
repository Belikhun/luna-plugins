package dev.belikhun.luna.vault.backend.mc12;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.core.mc12.ui.LunaChestMenu;
import dev.belikhun.luna.core.mc12.ui.LunaItems;
import dev.belikhun.luna.core.mc12.ui.LunaMenuHost;
import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.vault.VaultMoney;
import dev.belikhun.luna.legacy.vault.VaultTransactionPage;
import dev.belikhun.luna.legacy.vault.VaultTransactionRecord;
import dev.belikhun.luna.legacy.vault.runtime.VaultGateway;

import net.minecraft.entity.player.EntityPlayerMP;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * `/transactions`: the player's own money in and out, a page at a time.
 *
 * The same screen every other backend draws, down to the slots - 45 entries, the
 * arrows at 45 and 53, the summary book at 49 and the door at 52 - because a
 * player moving between a 1.12.2 and a modern backend should not have to relearn
 * where anything is.
 *
 * Item names are the modern ones, as everywhere else: `LegacyItemNames` turns them
 * into this version's names and damage values, so nothing here has to know that the
 * flattening happened.
 */
public final class TransactionHistoryScreen {
	private static final int ENTRY_CAPACITY = 45;
	private static final int SLOT_EMPTY_NOTICE = 22;
	private static final int SLOT_PREVIOUS_PAGE = 45;
	private static final int SLOT_SUMMARY = 49;
	private static final int SLOT_CLOSE = 52;
	private static final int SLOT_NEXT_PAGE = 53;

	private static final String ITEM_OUTGOING = "red_dye";
	private static final String ITEM_INCOMING = "lime_dye";
	private static final String ITEM_DOOR = "oak_door";

	private final PlayerBridge<EntityPlayerMP> players;
	private final VaultGateway<EntityPlayerMP> gateway;
	private final YamlConfigFile coreConfig;
	private final int pageSize;
	private final LunaMenuHost menuHost;

	public TransactionHistoryScreen(
		PlayerBridge<EntityPlayerMP> players,
		VaultGateway<EntityPlayerMP> gateway,
		YamlConfigFile coreConfig,
		int pageSize
	) {
		this.players = players;
		this.gateway = gateway;
		this.coreConfig = coreConfig;
		this.pageSize = Math.max(9, pageSize);
		this.menuHost = new LunaMenuHost(6);
	}

	/**
	 * Fetch a page and show it.
	 *
	 * The fetch may be an rpc to the proxy, so it finishes off the server thread;
	 * opening a menu does not, which is what the hop back through the bridge is for.
	 */
	public void open(EntityPlayerMP player, final int page) {
		if (player == null) {
			return;
		}

		final UUID playerId = players.idOf(player);

		gateway.history(playerId, page, pageSize).whenComplete((historyPage, throwable) ->
			players.onServerThread(() -> {
				// the player may have left while the proxy was answering
				EntityPlayerMP current = players.byId(playerId);

				if (current == null) {
					return;
				}

				if (throwable != null) {
					LunaTextComponents.send(current, "<red>❌ Không thể tải lịch sử giao dịch từ proxy.</red>");
					return;
				}

				render(current, historyPage);
			}));
	}

	public void forget(UUID playerId) {
		menuHost.forget(playerId);
	}

	public void close() {
		menuHost.closeAll();
	}

	private void render(final EntityPlayerMP player, final VaultTransactionPage page) {
		menuHost.open(
			player,
			LunaTextComponents.mini("<gradient:#6DFFD4:#4EA3FF>LunaVault</gradient> <dark_gray>›</dark_gray> <white>Lịch sử</white>"),
			menu -> draw(player, menu, page)
		);
	}

	private void draw(final EntityPlayerMP player, LunaChestMenu menu, final VaultTransactionPage page) {
		menu.clearTopSlots();

		List<VaultTransactionRecord> entries = page.entries();

		for (int index = 0; index < entries.size() && index < ENTRY_CAPACITY; index += 1) {
			VaultTransactionRecord entry = entries.get(index);
			boolean outgoing = players.idOf(player).equals(entry.senderId());

			menu.setDecoration(index, LunaItems.of(
				outgoing ? ITEM_OUTGOING : ITEM_INCOMING,
				outgoing ? "<red>⬤ Đã gửi</red>" : "<green>⬤ Đã nhận</green>",
				loreFor(entry)
			));
		}

		if (entries.isEmpty()) {
			menu.setDecoration(SLOT_EMPTY_NOTICE, LunaItems.of("paper", "<yellow>ℹ Chưa có giao dịch</yellow>", Arrays.asList(
				"<gray>Lịch sử của bạn đang trống.</gray>",
				"<gray>Hãy thử lại sau khi có giao dịch mới.</gray>"
			)));
		}

		if (page.page() > 0) {
			menu.setTopSlot(
				SLOT_PREVIOUS_PAGE,
				LunaItems.of("arrow", "<yellow>← Trang trước</yellow>", Collections.<String>emptyList()),
				() -> open(player, page.page() - 1)
			);
		}

		if (page.page() < page.maxPage()) {
			menu.setTopSlot(
				SLOT_NEXT_PAGE,
				LunaItems.of("arrow", "<yellow>Trang sau →</yellow>", Collections.<String>emptyList()),
				() -> open(player, page.page() + 1)
			);
		}

		menu.setDecoration(SLOT_SUMMARY, LunaItems.of("book", "<aqua>ℹ Thông tin</aqua>", Arrays.asList(
			"<white>Tổng giao dịch: <yellow>" + page.totalCount() + "</yellow></white>",
			"<white>Trang hiện tại: <yellow>" + (page.page() + 1) + "/" + (page.maxPage() + 1) + "</yellow></white>"
		)));

		menu.setTopSlot(
			SLOT_CLOSE,
			LunaItems.of(ITEM_DOOR, "<red>Đóng</red>", Collections.<String>emptyList()),
			() -> menuHost.close(player)
		);
	}

	private List<String> loreFor(VaultTransactionRecord entry) {
		List<String> lore = new ArrayList<String>();

		lore.add("<white>Người gửi: <yellow>" + safeName(entry.senderName()) + "</yellow></white>");
		lore.add("<white>Người nhận: <yellow>" + safeName(entry.receiverName()) + "</yellow></white>");
		lore.add("<white>Số tiền: <gold>" + formatMinor(entry.amountMinor()) + "</gold></white>");
		lore.add("<white>Nguồn: <aqua>" + safeName(entry.source()) + "</aqua></white>");

		if (!Strings.isBlank(entry.details())) {
			lore.add("<white>Ghi chú: <gray>" + entry.details() + "</gray></white>");
		}

		lore.add("<white>Thời gian: <gray>" + Formatters.date(Instant.ofEpochMilli(entry.completedAt())) + "</gray></white>");

		return lore;
	}

	private String formatMinor(long minor) {
		return Formatters.money(coreConfig, minor, VaultMoney.SCALE);
	}

	private String safeName(String value) {
		return Strings.isBlank(value) ? "Hệ thống" : value;
	}
}
