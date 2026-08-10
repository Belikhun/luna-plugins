package dev.belikhun.luna.shop.mc.service;

import dev.belikhun.luna.core.mc.compat.ItemDecor;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.gui.LunaPagination;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.shop.api.ShopResult;
import dev.belikhun.luna.shop.api.ShopTransactionEntry;
import dev.belikhun.luna.shop.api.ShopTransactionPlayer;
import dev.belikhun.luna.shop.api.ShopTransactionStore;
import dev.belikhun.luna.shop.mc.economy.ShopEconomyService;
import dev.belikhun.luna.shop.mc.model.ShopItem;
import dev.belikhun.luna.shop.mc.store.ShopItemStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

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
public final class ShopService {
	private static final DateTimeFormatter RESET_TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);
	private static final int STORAGE_SLOTS = 36;

	private final MinecraftServer server;
	private final ShopEconomyService economy;
	private final ShopItemStore store;
	private final ShopTradeLimitService tradeLimitService;
	private final ShopTransactionStore transactionStore;
	private final YamlConfigFile coreConfig;
	private final LunaLogger logger;

	public ShopService(
		MinecraftServer server,
		ShopEconomyService economy,
		ShopItemStore store,
		ShopTradeLimitService tradeLimitService,
		ShopTransactionStore transactionStore,
		YamlConfigFile coreConfig,
		LunaLogger logger
	) {
		this.server = server;
		this.economy = economy;
		this.store = store;
		this.tradeLimitService = tradeLimitService;
		this.transactionStore = transactionStore;
		this.coreConfig = coreConfig;
		this.logger = logger;
	}

	public ShopEconomyService economy() {
		return economy;
	}

	public ShopItemStore store() {
		return store;
	}

	public String formatMoney(double amount) {
		return Formatters.money(coreConfig, amount);
	}

	public int remainingBuyLimit(ServerPlayer player, ShopItem shopItem) {
		return tradeLimitService.remainingBuy(player.getUUID(), shopItem);
	}

	public int remainingSellLimit(ServerPlayer player, ShopItem shopItem) {
		return tradeLimitService.remainingSell(player.getUUID(), shopItem);
	}

	public int capBuyAmount(ServerPlayer player, ShopItem shopItem, int requestedAmount) {
		return tradeLimitService.capBuyAmount(player.getUUID(), shopItem, requestedAmount);
	}

	public int capSellAmount(ServerPlayer player, ShopItem shopItem, int requestedAmount) {
		return tradeLimitService.capSellAmount(player.getUUID(), shopItem, requestedAmount);
	}

	public String tradeLimitResetDuration() {
		return Formatters.duration(Duration.ofMillis(tradeLimitService.millisUntilReset()));
	}

	public String tradeLimitResetTimeText() {
		Instant resetAt = Instant.now().plusMillis(Math.max(0L, tradeLimitService.millisUntilReset()));
		return "vào lúc " + RESET_TIME_FORMATTER.format(resetAt.atZone(ZoneId.systemDefault()));
	}

	public ShopResult buy(ServerPlayer player, ShopItem shopItem, int amount) {
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

		ItemStack sample = shopItem.itemStack(server);

		if (sample.isEmpty()) {
			return fail("BUY", player, shopItem, tradeAmount, "Không đọc được dữ liệu vật phẩm.", "<red>❌ Vật phẩm này đang lỗi dữ liệu.</red>", total);
		}

		if (maxAcceptable(player.getInventory(), sample) < tradeAmount) {
			return fail("BUY", player, shopItem, tradeAmount, "Túi đồ không đủ chỗ.", "<red>❌ Túi đồ không đủ chỗ chứa số lượng đã chọn.</red>", total);
		}

		if (!economy.withdraw(player, total)) {
			return fail("BUY", player, shopItem, tradeAmount, "Không thể trừ tiền từ ví người chơi.", "<red>❌ Không thể trừ tiền từ ví của bạn.</red>", total);
		}

		if (!tradeLimitService.consumeBuy(player.getUUID(), shopItem, tradeAmount)) {
			economy.deposit(player, total);
			return fail("BUY", player, shopItem, tradeAmount, "Đã đạt giới hạn mua trong ngày.", "<red>❌ Hạn mức mua vừa thay đổi, vui lòng thử lại.</red>", total);
		}

		give(player, sample, tradeAmount);
		logSuccess("BUY", player, shopItem, tradeAmount, total);

		if (tradeAmount < amount) {
			return ShopResult.ok("<yellow>⚠ Giới hạn trong ngày chỉ còn <white>" + tradeAmount + "</white>. Đã mua với giá " + formatMoney(total) + ".</yellow>");
		}

		return ShopResult.ok("<green>✔ Mua thành công <white>" + tradeAmount + "</white> vật phẩm với giá " + formatMoney(total) + ".</green>");
	}

	public ShopResult sell(ServerPlayer player, ShopItem shopItem, int amount) {
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

		ItemStack sample = shopItem.itemStack(server);

		if (sample.isEmpty()) {
			return fail("SELL", player, shopItem, tradeAmount, "Không đọc được dữ liệu vật phẩm.", "<red>❌ Vật phẩm này đang lỗi dữ liệu.</red>", 0D);
		}

		if (countSimilar(player.getInventory(), sample) < tradeAmount) {
			return fail("SELL", player, shopItem, tradeAmount, "Không đủ vật phẩm để bán.", "<red>❌ Bạn không đủ vật phẩm để bán.</red>", shopItem.sellPrice() * tradeAmount);
		}

		removeSimilar(player.getInventory(), sample, tradeAmount);
		double total = shopItem.sellPrice() * tradeAmount;

		if (!economy.deposit(player, total)) {
			give(player, sample, tradeAmount);
			return fail("SELL", player, shopItem, tradeAmount, "Không thể cộng tiền vào ví người chơi.", "<red>❌ Không thể cộng tiền vào ví của bạn.</red>", total);
		}

		if (!tradeLimitService.consumeSell(player.getUUID(), shopItem, tradeAmount)) {
			economy.withdraw(player, total);
			give(player, sample, tradeAmount);
			return fail("SELL", player, shopItem, tradeAmount, "Đã đạt giới hạn bán trong ngày.", "<red>❌ Hạn mức bán vừa thay đổi, vui lòng thử lại.</red>", total);
		}

		logSuccess("SELL", player, shopItem, tradeAmount, total);

		if (tradeAmount < amount) {
			return ShopResult.ok("<yellow>⚠ Giới hạn trong ngày chỉ còn <white>" + tradeAmount + "</white>. Đã bán và nhận " + formatMoney(total) + ".</yellow>");
		}

		return ShopResult.ok("<green>✔ Bán thành công <white>" + tradeAmount + "</white> vật phẩm và nhận " + formatMoney(total) + ".</green>");
	}

	public ShopResult sellAllSimilar(ServerPlayer player, ShopItem shopItem) {
		int owned = countSimilar(player.getInventory(), shopItem.itemStack(server));

		if (owned <= 0) {
			return ShopResult.fail("<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>");
		}

		return sell(player, shopItem, owned);
	}

	/** How many of this item the player is carrying, across the storage slots. */
	public int countSimilar(Inventory inventory, ItemStack sample) {
		if (sample == null || sample.isEmpty()) {
			return 0;
		}

		int total = 0;
		int slots = Math.min(STORAGE_SLOTS, inventory.getContainerSize());

		for (int slot = 0; slot < slots; slot++) {
			ItemStack content = inventory.getItem(slot);

			if (!content.isEmpty() && ItemDecor.sameItemAndData(content, sample)) {
				total += content.getCount();
			}
		}

		return total;
	}

	private void give(ServerPlayer player, ItemStack sample, int amount) {
		int remaining = amount;
		int maxStack = Math.max(1, sample.getMaxStackSize());

		while (remaining > 0) {
			int giveAmount = Math.min(maxStack, remaining);
			ItemStack stack = sample.copyWithCount(giveAmount);

			// anything that would not fit goes to the ground rather than vanishing;
			// the caller already checked there was room, so this is the belt to that
			// brace - a hopper or another mod can still take a slot mid-trade
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false);
			}

			remaining -= giveAmount;
		}
	}

	private int maxAcceptable(Inventory inventory, ItemStack sample) {
		int maxStack = Math.max(1, sample.getMaxStackSize());
		int space = 0;
		int slots = Math.min(STORAGE_SLOTS, inventory.getContainerSize());

		for (int slot = 0; slot < slots; slot++) {
			ItemStack content = inventory.getItem(slot);

			if (content.isEmpty()) {
				space += maxStack;
				continue;
			}

			if (ItemDecor.sameItemAndData(content, sample)) {
				space += Math.max(0, maxStack - content.getCount());
			}
		}

		return space;
	}

	private void removeSimilar(Inventory inventory, ItemStack sample, int amount) {
		int remaining = amount;
		int slots = Math.min(STORAGE_SLOTS, inventory.getContainerSize());

		for (int slot = 0; slot < slots && remaining > 0; slot++) {
			ItemStack content = inventory.getItem(slot);

			if (content.isEmpty() || !ItemDecor.sameItemAndData(content, sample)) {
				continue;
			}

			if (content.getCount() <= remaining) {
				remaining -= content.getCount();
				inventory.setItem(slot, ItemStack.EMPTY);
				continue;
			}

			content.setCount(content.getCount() - remaining);
			inventory.setItem(slot, content);
			remaining = 0;
		}
	}

	private ShopResult fail(String action, ServerPlayer player, ShopItem item, int amount, String reason, String message, double total) {
		recordTransaction(action, player, item, amount, total, false, reason);
		logFailure(action, player, item, amount, reason, total);
		return ShopResult.fail(message);
	}

	private void logSuccess(String action, ServerPlayer player, ShopItem item, int amount, double total) {
		recordTransaction(action, player, item, amount, total, true, "OK");

		if (logger == null) {
			return;
		}

		logger.audit("TX " + Formatters.stripFormats(action)
			+ " | player=" + Formatters.stripFormats(player.getName().getString())
			+ " (" + player.getUUID() + ")"
			+ " | item=" + Formatters.stripFormats(item.id())
			+ " | category=" + Formatters.stripFormats(item.category())
			+ " | amount=" + amount
			+ " | total=" + Formatters.stripFormats(formatMoney(total))
			+ " | result=SUCCESS");
	}

	private void logFailure(String action, ServerPlayer player, ShopItem item, int amount, String reason, double total) {
		if (logger == null) {
			return;
		}

		logger.warn("TX " + Formatters.stripFormats(action)
			+ " | player=" + Formatters.stripFormats(player.getName().getString())
			+ " (" + player.getUUID() + ")"
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

	private void recordTransaction(String action, ServerPlayer player, ShopItem item, int amount, double total, boolean success, String reason) {
		if (transactionStore == null || player == null || item == null) {
			return;
		}

		double unitPrice = "BUY".equalsIgnoreCase(action) ? item.buyPrice() : item.sellPrice();
		ShopTransactionEntry entry = new ShopTransactionEntry(
			UUID.randomUUID().toString(),
			player.getUUID().toString(),
			player.getName().getString(),
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

	public record ShopHistoryPage(int total, int maxPage, int currentPage, List<ShopTransactionEntry> entries) {
	}
}
