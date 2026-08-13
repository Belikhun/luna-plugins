package dev.belikhun.luna.legacy.shop;

import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.ui.LunaPagination;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.string.Formatters;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Buying and selling: the rules, in the order they are checked.
 *
 * Every failure path leaves the player exactly as they were, which is why the
 * money moves before the items and each step undoes the one before it when the
 * next refuses. The daily limit is consumed last on purpose: it is the only
 * check that can change between being read and being applied.
 *
 * Only the storage half of a player's inventory is ever counted or filled. Armor
 * and the offhand are not places a shop puts things, and counting them would let
 * a player sell what they are wearing.
 */
public final class ShopService<P, I> {
	private static final DateTimeFormatter RESET_TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);

	private final ShopItems<I> codec;
	private final ShopInventory<P, I> inventory;
	private final PlayerBridge<P> players;
	private final ShopEconomyService<P> economy;
	private final ShopItemStore<I> store;
	private final ShopTradeLimitService tradeLimitService;
	private final ShopTransactionStore transactionStore;
	private final YamlConfigFile coreConfig;
	private final LunaLogger logger;

	public ShopService(
		ShopItems<I> codec,
		ShopInventory<P, I> inventory,
		PlayerBridge<P> players,
		ShopEconomyService<P> economy,
		ShopItemStore<I> store,
		ShopTradeLimitService tradeLimitService,
		ShopTransactionStore transactionStore,
		YamlConfigFile coreConfig,
		LunaLogger logger
	) {
		this.codec = codec;
		this.inventory = inventory;
		this.players = players;
		this.economy = economy;
		this.store = store;
		this.tradeLimitService = tradeLimitService;
		this.transactionStore = transactionStore;
		this.coreConfig = coreConfig;
		this.logger = logger;
	}

	public ShopEconomyService<P> economy() {
		return economy;
	}

	public ShopItemStore<I> store() {
		return store;
	}

	public String formatMoney(double amount) {
		return Formatters.money(coreConfig, amount);
	}

	public int remainingBuyLimit(P player, ShopItem shopItem) {
		return tradeLimitService.remainingBuy(players.idOf(player), shopItem);
	}

	public int remainingSellLimit(P player, ShopItem shopItem) {
		return tradeLimitService.remainingSell(players.idOf(player), shopItem);
	}

	public int capBuyAmount(P player, ShopItem shopItem, int requestedAmount) {
		return tradeLimitService.capBuyAmount(players.idOf(player), shopItem, requestedAmount);
	}

	public int capSellAmount(P player, ShopItem shopItem, int requestedAmount) {
		return tradeLimitService.capSellAmount(players.idOf(player), shopItem, requestedAmount);
	}

	public String tradeLimitResetDuration() {
		return Formatters.duration(Duration.ofMillis(tradeLimitService.millisUntilReset()));
	}

	public String tradeLimitResetTimeText() {
		Instant resetAt = Instant.now().plusMillis(Math.max(0L, tradeLimitService.millisUntilReset()));
		return "vào lúc " + RESET_TIME_FORMATTER.format(resetAt.atZone(ZoneId.systemDefault()));
	}

	public ShopResult buy(P player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return fail("BUY", player, shopItem, amount, "Số lượng mua không hợp lệ.", "<red>❌ Số lượng mua không hợp lệ.</red>", 0D);
		}

		int tradeAmount = capBuyAmount(player, shopItem, amount);

		if (tradeAmount <= 0) {
			return fail("BUY", player, shopItem, amount, "Đã đạt giới hạn mua trong ngày.",
				"<red>❌ Bạn đã chạm giới hạn mua hôm nay. Reset <white>" + tradeLimitResetTimeText() + "</white>.</red>", 0D);
		}

		double total = shopItem.buyPrice() * tradeAmount;

		if (shopItem.buyPrice() <= 0D) {
			return fail("BUY", player, shopItem, tradeAmount, "Vật phẩm không thể mua.", "<red>❌ Vật phẩm này không thể mua.</red>", total);
		}

		if (!economy.has(player, total)) {
			return fail("BUY", player, shopItem, tradeAmount, "Không đủ tiền để mua.", "<red>❌ Bạn không đủ tiền để mua.</red>", total);
		}

		I sample = shopItem.itemStack(codec);

		if (codec.isEmpty(sample)) {
			return fail("BUY", player, shopItem, tradeAmount, "Không đọc được dữ liệu vật phẩm.", "<red>❌ Vật phẩm này đang lỗi dữ liệu.</red>", total);
		}

		if (inventory.maxAcceptable(player, sample) < tradeAmount) {
			return fail("BUY", player, shopItem, tradeAmount, "Túi đồ không đủ chỗ.", "<red>❌ Túi đồ không đủ chỗ chứa số lượng đã chọn.</red>", total);
		}

		if (!economy.withdraw(player, total)) {
			return fail("BUY", player, shopItem, tradeAmount, "Không thể trừ tiền từ ví người chơi.", "<red>❌ Không thể trừ tiền từ ví của bạn.</red>", total);
		}

		if (!tradeLimitService.consumeBuy(players.idOf(player), shopItem, tradeAmount)) {
			economy.deposit(player, total);
			return fail("BUY", player, shopItem, tradeAmount, "Đã đạt giới hạn mua trong ngày.", "<red>❌ Hạn mức mua vừa thay đổi, vui lòng thử lại.</red>", total);
		}

		inventory.give(player, sample, tradeAmount);
		logSuccess("BUY", player, shopItem, tradeAmount, total);

		if (tradeAmount < amount) {
			return ShopResult.ok("<yellow>⚠ Giới hạn trong ngày chỉ còn <white>" + tradeAmount + "</white>. Đã mua với giá " + formatMoney(total) + ".</yellow>");
		}

		return ShopResult.ok("<green>✔ Mua thành công <white>" + tradeAmount + "</white> vật phẩm với giá " + formatMoney(total) + ".</green>");
	}

	public ShopResult sell(P player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return fail("SELL", player, shopItem, amount, "Số lượng bán không hợp lệ.", "<red>❌ Số lượng bán không hợp lệ.</red>", 0D);
		}

		int tradeAmount = capSellAmount(player, shopItem, amount);

		if (tradeAmount <= 0) {
			return fail("SELL", player, shopItem, amount, "Đã đạt giới hạn bán trong ngày.",
				"<red>❌ Bạn đã chạm giới hạn bán hôm nay. Reset <white>" + tradeLimitResetTimeText() + "</white>.</red>", 0D);
		}

		if (shopItem.sellPrice() <= 0D) {
			return fail("SELL", player, shopItem, tradeAmount, "Vật phẩm không thể bán.", "<red>❌ Vật phẩm này không thể bán.</red>", 0D);
		}

		I sample = shopItem.itemStack(codec);

		if (codec.isEmpty(sample)) {
			return fail("SELL", player, shopItem, tradeAmount, "Không đọc được dữ liệu vật phẩm.", "<red>❌ Vật phẩm này đang lỗi dữ liệu.</red>", 0D);
		}

		if (inventory.countSimilar(player, sample) < tradeAmount) {
			return fail("SELL", player, shopItem, tradeAmount, "Không đủ vật phẩm để bán.", "<red>❌ Bạn không đủ vật phẩm để bán.</red>", shopItem.sellPrice() * tradeAmount);
		}

		inventory.removeSimilar(player, sample, tradeAmount);
		double total = shopItem.sellPrice() * tradeAmount;

		if (!economy.deposit(player, total)) {
			inventory.give(player, sample, tradeAmount);
			return fail("SELL", player, shopItem, tradeAmount, "Không thể cộng tiền vào ví người chơi.", "<red>❌ Không thể cộng tiền vào ví của bạn.</red>", total);
		}

		if (!tradeLimitService.consumeSell(players.idOf(player), shopItem, tradeAmount)) {
			economy.withdraw(player, total);
			inventory.give(player, sample, tradeAmount);
			return fail("SELL", player, shopItem, tradeAmount, "Đã đạt giới hạn bán trong ngày.", "<red>❌ Hạn mức bán vừa thay đổi, vui lòng thử lại.</red>", total);
		}

		logSuccess("SELL", player, shopItem, tradeAmount, total);

		if (tradeAmount < amount) {
			return ShopResult.ok("<yellow>⚠ Giới hạn trong ngày chỉ còn <white>" + tradeAmount + "</white>. Đã bán và nhận " + formatMoney(total) + ".</yellow>");
		}

		return ShopResult.ok("<green>✔ Bán thành công <white>" + tradeAmount + "</white> vật phẩm và nhận " + formatMoney(total) + ".</green>");
	}

	public ShopResult sellAllSimilar(P player, ShopItem shopItem) {
		int owned = inventory.countSimilar(player, shopItem.itemStack(codec));

		if (owned <= 0) {
			return ShopResult.fail("<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>");
		}

		return sell(player, shopItem, owned);
	}

	/**
	 * How many of this item the player is carrying, across the storage slots.
	 *
	 * Kept on the service rather than left to callers so the GUI and the commands
	 * ask the same question the trade rules do.
	 */
	public int countSimilar(P player, I sample) {
		if (sample == null || codec.isEmpty(sample)) {
			return 0;
		}

		return inventory.countSimilar(player, sample);
	}

	private ShopResult fail(String action, P player, ShopItem item, int amount, String reason, String message, double total) {
		recordTransaction(action, player, item, amount, total, false, reason);
		logFailure(action, player, item, amount, reason, total);
		return ShopResult.fail(message);
	}

	private void logSuccess(String action, P player, ShopItem item, int amount, double total) {
		recordTransaction(action, player, item, amount, total, true, "OK");

		if (logger == null) {
			return;
		}

		logger.audit("TX " + Formatters.stripFormats(action)
			+ " | player=" + Formatters.stripFormats(players.nameOf(player))
			+ " (" + players.idOf(player) + ")"
			+ " | item=" + Formatters.stripFormats(item.id())
			+ " | category=" + Formatters.stripFormats(item.category())
			+ " | amount=" + amount
			+ " | total=" + Formatters.stripFormats(formatMoney(total))
			+ " | result=SUCCESS");
	}

	private void logFailure(String action, P player, ShopItem item, int amount, String reason, double total) {
		if (logger == null) {
			return;
		}

		logger.warn("TX " + Formatters.stripFormats(action)
			+ " | player=" + Formatters.stripFormats(players.nameOf(player))
			+ " (" + players.idOf(player) + ")"
			+ " | item=" + Formatters.stripFormats(item.id())
			+ " | category=" + Formatters.stripFormats(item.category())
			+ " | amount=" + amount
			+ " | total=" + Formatters.stripFormats(formatMoney(total))
			+ " | result=FAILED"
			+ " | reason=" + Formatters.stripFormats(reason));
	}

	public CompletableFuture<ShopHistoryPage> transactionHistoryPageAsync(UUID playerUuid, int page, int pageSize) {
		return CompletableFuture.supplyAsync(() -> {
			int total = transactionStore.countByPlayer(playerUuid);
			int maxPage = LunaPagination.maxPage(total, pageSize);
			int currentPage = LunaPagination.clampPage(page, maxPage);
			return new ShopHistoryPage(total, maxPage, currentPage, transactionStore.findByPlayer(playerUuid, currentPage, pageSize));
		});
	}

	public boolean isTransactionHistoryEnabled() {
		return transactionStore.isEnabled();
	}

	public Optional<ShopTransactionPlayer> findHistoricalPlayer(String playerName) {
		return transactionStore.findLatestPlayerByName(playerName);
	}

	public List<String> suggestHistoricalPlayers(String input, int limit) {
		return transactionStore.suggestPlayerNames(input, limit);
	}

	private void recordTransaction(String action, P player, ShopItem item, int amount, double total, boolean success, String reason) {
		if (transactionStore == null || player == null || item == null) {
			return;
		}

		double unitPrice = "BUY".equalsIgnoreCase(action) ? item.buyPrice() : item.sellPrice();
		ShopTransactionEntry entry = new ShopTransactionEntry(
			UUID.randomUUID().toString(),
			players.idOf(player).toString(),
			players.nameOf(player),
			action,
			item.id(),
			item.category(),
			amount,
			unitPrice,
			total,
			success,
			reason,
			Instant.now().toEpochMilli()
		);

		// the insert is a database round trip and the caller is mid-click
		CompletableFuture.runAsync(() -> transactionStore.insert(entry));
	}

	public static final class ShopHistoryPage {
		private final int total;
		private final int maxPage;
		private final int currentPage;
		private final List<ShopTransactionEntry> entries;

		public ShopHistoryPage(int total, int maxPage, int currentPage, List<ShopTransactionEntry> entries) {
			this.total = total;
			this.maxPage = maxPage;
			this.currentPage = currentPage;
			this.entries = entries;
		}

		public int total() {
			return total;
		}

		public int maxPage() {
			return maxPage;
		}

		public int currentPage() {
			return currentPage;
		}

		public List<ShopTransactionEntry> entries() {
			return entries;
		}

		}
}
