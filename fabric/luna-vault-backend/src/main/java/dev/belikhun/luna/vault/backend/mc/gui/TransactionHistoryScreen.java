package dev.belikhun.luna.vault.backend.mc.gui;

import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.ui.LunaChestMenuBase;
import dev.belikhun.luna.core.mc.ui.LunaItems;
import dev.belikhun.luna.core.mc.ui.LunaMenuHost;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.backend.mc.service.VaultGateway;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "/transactions": the player's own money in and out, a page at a time.
 *
 * The same screen the Paper backend draws, down to the slots - 45 entries, the
 * arrows at 45 and 53, the summary book at 49 and the door at 52 - because a
 * player moving between a paper and a fabric backend should not have to relearn
 * where anything is.
 */
public final class TransactionHistoryScreen {
	private static final int ENTRY_CAPACITY = 45;
	private static final int SLOT_EMPTY_NOTICE = 22;
	private static final int SLOT_PREVIOUS_PAGE = 45;
	private static final int SLOT_SUMMARY = 49;
	private static final int SLOT_CLOSE = 52;
	private static final int SLOT_NEXT_PAGE = 53;

	private final MinecraftServer server;
	private final VaultGateway gateway;
	private final YamlConfigFile coreConfig;
	private final int pageSize;
	private final LunaMenuHost menuHost;

	public TransactionHistoryScreen(MinecraftServer server, VaultGateway gateway, YamlConfigFile coreConfig, int pageSize) {
		this.server = server;
		this.gateway = gateway;
		this.coreConfig = coreConfig;
		this.pageSize = Math.max(9, pageSize);
		this.menuHost = new LunaMenuHost(6);
	}

	/**
	 * Fetch a page and show it.
	 *
	 * The fetch may be an rpc to the proxy, so it finishes off the server thread;
	 * opening a menu does not, which is what the hop back through
	 * {@code server.execute} is for.
	 */
	public void open(ServerPlayer player, int page) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();

		gateway.history(playerId, page, pageSize).whenComplete((historyPage, throwable) -> server.execute(() -> {
			ServerPlayer current = server.getPlayerList().getPlayer(playerId);

			if (current == null) {
				return;
			}

			if (throwable != null) {
				current.sendSystemMessage(LunaTextComponents.mini("<red>❌ Không thể tải lịch sử giao dịch từ proxy.</red>"));
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

	private void render(ServerPlayer player, VaultTransactionPage page) {
		menuHost.open(
			player,
			LunaTextComponents.mini("<gradient:#6DFFD4:#4EA3FF>LunaVault</gradient> <dark_gray>›</dark_gray> <white>Lịch sử</white>"),
			menu -> draw(player, menu, page)
		);
	}

	private void draw(ServerPlayer player, LunaChestMenuBase menu, VaultTransactionPage page) {
		menu.clearTopSlots();

		List<VaultTransactionRecord> entries = page.entries();

		for (int index = 0; index < entries.size() && index < ENTRY_CAPACITY; index++) {
			VaultTransactionRecord entry = entries.get(index);
			boolean outgoing = player.getUUID().equals(entry.senderId());
			String material = outgoing ? "red_dye" : "lime_dye";
			String title = outgoing ? "<red>⬤ Đã gửi</red>" : "<green>⬤ Đã nhận</green>";
			menu.setDecoration(index, LunaItems.of(material, title, loreFor(entry)));
		}

		if (entries.isEmpty()) {
			menu.setDecoration(SLOT_EMPTY_NOTICE, LunaItems.of("paper", "<yellow>ℹ Chưa có giao dịch</yellow>", List.of(
				"<gray>Lịch sử của bạn đang trống.</gray>",
				"<gray>Hãy thử lại sau khi có giao dịch mới.</gray>"
			)));
		}

		if (page.page() > 0) {
			menu.setTopSlot(
				SLOT_PREVIOUS_PAGE,
				LunaItems.of("arrow", "<yellow>← Trang trước</yellow>", List.of()),
				() -> open(player, page.page() - 1)
			);
		}

		if (page.page() < page.maxPage()) {
			menu.setTopSlot(
				SLOT_NEXT_PAGE,
				LunaItems.of("arrow", "<yellow>Trang sau →</yellow>", List.of()),
				() -> open(player, page.page() + 1)
			);
		}

		menu.setDecoration(SLOT_SUMMARY, LunaItems.of("book", "<aqua>ℹ Thông tin</aqua>", List.of(
			"<white>Tổng giao dịch: <yellow>" + page.totalCount() + "</yellow></white>",
			"<white>Trang hiện tại: <yellow>" + (page.page() + 1) + "/" + (page.maxPage() + 1) + "</yellow></white>"
		)));

		menu.setTopSlot(
			SLOT_CLOSE,
			LunaItems.of("oak_door", "<red>Đóng</red>", List.of()),
			player::closeContainer
		);
	}

	private List<String> loreFor(VaultTransactionRecord entry) {
		List<String> lore = new ArrayList<>();

		lore.add("<white>Người gửi: <yellow>" + safeName(entry.senderName()) + "</yellow></white>");
		lore.add("<white>Người nhận: <yellow>" + safeName(entry.receiverName()) + "</yellow></white>");
		lore.add("<white>Số tiền: <gold>" + formatMinor(entry.amountMinor()) + "</gold></white>");
		lore.add("<white>Nguồn: <aqua>" + safeName(entry.source()) + "</aqua></white>");

		if (entry.details() != null && !entry.details().isBlank()) {
			lore.add("<white>Ghi chú: <gray>" + entry.details() + "</gray></white>");
		}

		lore.add("<white>Thời gian: <gray>" + Formatters.date(Instant.ofEpochMilli(entry.completedAt())) + "</gray></white>");
		return lore;
	}

	private String formatMinor(long minor) {
		return Formatters.money(coreConfig, minor, VaultMoney.SCALE);
	}

	private String safeName(String value) {
		return value == null || value.isBlank() ? "Hệ thống" : value;
	}
}
