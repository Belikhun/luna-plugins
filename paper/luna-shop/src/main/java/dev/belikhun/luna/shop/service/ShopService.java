package dev.belikhun.luna.shop.service;

import dev.belikhun.luna.shop.api.ShopResult;
import dev.belikhun.luna.shop.api.ShopTransactionEntry;
import dev.belikhun.luna.shop.api.ShopTransactionPlayer;
import dev.belikhun.luna.shop.api.ShopTransactionStore;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.gui.LunaPagination;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.shop.economy.ShopEconomyService;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.store.ShopItemStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ShopService {
	private static final DateTimeFormatter RESET_TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);

	private final JavaPlugin plugin;
	private final ShopEconomyService economy;
	private final ShopItemStore store;
	private final ShopTradeLimitService tradeLimitService;
	private final ShopTransactionStore transactionStore;
	private final LunaLogger logger;

	/** Players with a trade already waiting on the wallet; see beginTrade. */
	private final Set<UUID> tradesInFlight = ConcurrentHashMap.newKeySet();

	public ShopService(JavaPlugin plugin, ShopEconomyService economy, ShopItemStore store, ShopTradeLimitService tradeLimitService, ShopTransactionStore transactionStore, LunaLogger logger) {
		this.plugin = plugin;
		this.economy = economy;
		this.store = store;
		this.tradeLimitService = tradeLimitService;
		this.transactionStore = transactionStore;
		this.logger = logger;
	}

	public ShopEconomyService economy() {
		return economy;
	}

	public String formatMoney(double amount) {
		return Formatters.money(LunaCore.services().configStore(), amount);
	}

	public int remainingBuyLimit(Player player, ShopItem shopItem) {
		return tradeLimitService.remainingBuy(player.getUniqueId(), shopItem);
	}

	public int remainingSellLimit(Player player, ShopItem shopItem) {
		return tradeLimitService.remainingSell(player.getUniqueId(), shopItem);
	}

	public int capBuyAmount(Player player, ShopItem shopItem, int requestedAmount) {
		return tradeLimitService.capBuyAmount(player.getUniqueId(), shopItem, requestedAmount);
	}

	public int capSellAmount(Player player, ShopItem shopItem, int requestedAmount) {
		return tradeLimitService.capSellAmount(player.getUniqueId(), shopItem, requestedAmount);
	}

	public String tradeLimitResetDuration() {
		return Formatters.duration(Duration.ofMillis(tradeLimitService.millisUntilReset()));
	}

	public String tradeLimitResetTimeText() {
		Instant resetAt = Instant.now().plusMillis(Math.max(0L, tradeLimitService.millisUntilReset()));
		String clock = RESET_TIME_FORMATTER.format(resetAt.atZone(ZoneId.systemDefault()));
		return "vào lúc " + clock;
	}

	/**
	 * Buy, without holding the main thread for the wallet's answer.
	 *
	 * The work splits in three. Everything local - the limits, the price, the
	 * inventory space - is checked here, on the caller's thread, because all of it
	 * reads game state. The money then moves off that thread. What comes back is
	 * finished on the main thread again, because giving items is game state too.
	 *
	 * **The inventory is checked twice on purpose.** A round trip to the proxy is
	 * long enough for the player to fill their bag, and by then they have already
	 * been charged; the second check is what turns that into a refund instead of
	 * money for nothing.
	 *
	 * With a Vault wallet none of this defers: its async form completes inline, so
	 * the whole chain runs on the calling thread exactly as it used to.
	 */
	public CompletableFuture<ShopResult> buyAsync(Player player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return completed(fail("BUY", player, shopItem, amount, "Số lượng mua không hợp lệ.", "<red>❌ Số lượng mua không hợp lệ.</red>", 0D));
		}

		int tradeAmount = capBuyAmount(player, shopItem, amount);

		if (tradeAmount <= 0) {
			return completed(fail("BUY", player, shopItem, amount, "Đã đạt giới hạn mua trong ngày.",
				"<red>❌ Bạn đã chạm giới hạn mua hôm nay. Reset <white>" + tradeLimitResetTimeText() + "</white>.</red>", 0D));
		}

		double total = shopItem.buyPrice() * tradeAmount;

		if (shopItem.buyPrice() <= 0D) {
			return completed(fail("BUY", player, shopItem, tradeAmount, "Vật phẩm không thể mua.", "<red>❌ Vật phẩm này không thể mua.</red>", total));
		}

		ItemStack sample = shopItem.itemStack();

		if (maxAcceptable(player.getInventory(), sample) < tradeAmount) {
			return completed(fail("BUY", player, shopItem, tradeAmount, "Túi đồ không đủ chỗ.", "<red>❌ Túi đồ không đủ chỗ chứa số lượng đã chọn.</red>", total));
		}

		UUID playerId = player.getUniqueId();

		if (!beginTrade(playerId)) {
			return completed(fail("BUY", player, shopItem, tradeAmount, "Giao dịch trước chưa hoàn tất.",
				"<yellow>⚠ Giao dịch trước của bạn chưa xong. Vui lòng chờ một chút.</yellow>", total));
		}

		int finalTradeAmount = tradeAmount;

		return economy.hasAsync(player, total)
			.thenCompose(affordable -> {
				if (!Boolean.TRUE.equals(affordable)) {
					return completed(fail("BUY", player, shopItem, finalTradeAmount, "Không đủ tiền để mua.", "<red>❌ Bạn không đủ tiền để mua.</red>", total));
				}

				return economy.withdrawAsync(player, total).thenCompose(charged -> {
					if (!Boolean.TRUE.equals(charged)) {
						return completed(fail("BUY", player, shopItem, finalTradeAmount, "Không thể trừ tiền từ ví người chơi.", "<red>❌ Không thể trừ tiền từ ví của bạn.</red>", total));
					}

					return onMainThread(() -> finishBuy(player, shopItem, sample, amount, finalTradeAmount, total));
				});
			})
			.whenComplete((ignored, failure) -> endTrade(playerId));
	}

	/** The part that must be back on the main thread: the items and the limit. */
	private ShopResult finishBuy(Player player, ShopItem shopItem, ItemStack sample, int amount, int tradeAmount, double total) {
		if (maxAcceptable(player.getInventory(), sample) < tradeAmount) {
			refund("BUY", player, total);

			return fail("BUY", player, shopItem, tradeAmount, "Túi đồ không đủ chỗ.",
				"<red>❌ Túi đồ đã đầy trong lúc thanh toán. Tiền đã được hoàn lại.</red>", total);
		}

		if (!tradeLimitService.consumeBuy(player.getUniqueId(), shopItem, tradeAmount)) {
			refund("BUY", player, total);

			return fail("BUY", player, shopItem, tradeAmount, "Đã đạt giới hạn mua trong ngày.", "<red>❌ Hạn mức mua vừa thay đổi, vui lòng thử lại.</red>", total);
		}

		give(player.getInventory(), sample, tradeAmount);
		logSuccess("BUY", player, shopItem, tradeAmount, total);

		if (tradeAmount < amount) {
			return ShopResult.ok("<yellow>⚠ Giới hạn trong ngày chỉ còn <white>" + tradeAmount + "</white>. Đã mua với giá " + formatMoney(total) + ".</yellow>");
		}

		return ShopResult.ok("<green>✔ Mua thành công <white>" + tradeAmount + "</white> vật phẩm với giá " + formatMoney(total) + ".</green>");
	}

	/**
	 * Sell, without holding the main thread for the wallet's answer.
	 *
	 * The items leave the inventory before the money is asked for, exactly as the
	 * blocking version did, and are put back if the wallet refuses. That ordering
	 * matters more here than it did: the gap is now a whole round trip, and taking
	 * the goods first is what stops the same stack being sold twice in it.
	 */
	public CompletableFuture<ShopResult> sellAsync(Player player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return completed(fail("SELL", player, shopItem, amount, "Số lượng bán không hợp lệ.", "<red>❌ Số lượng bán không hợp lệ.</red>", 0D));
		}

		int tradeAmount = capSellAmount(player, shopItem, amount);

		if (tradeAmount <= 0) {
			return completed(fail("SELL", player, shopItem, amount, "Đã đạt giới hạn bán trong ngày.",
				"<red>❌ Bạn đã chạm giới hạn bán hôm nay. Reset <white>" + tradeLimitResetTimeText() + "</white>.</red>", 0D));
		}

		if (shopItem.sellPrice() <= 0D) {
			return completed(fail("SELL", player, shopItem, tradeAmount, "Vật phẩm không thể bán.", "<red>❌ Vật phẩm này không thể bán.</red>", 0D));
		}

		ItemStack sample = shopItem.itemStack();
		double total = shopItem.sellPrice() * tradeAmount;

		if (countSimilar(player.getInventory(), sample) < tradeAmount) {
			return completed(fail("SELL", player, shopItem, tradeAmount, "Không đủ vật phẩm để bán.", "<red>❌ Bạn không đủ vật phẩm để bán.</red>", total));
		}

		UUID playerId = player.getUniqueId();

		if (!beginTrade(playerId)) {
			return completed(fail("SELL", player, shopItem, tradeAmount, "Giao dịch trước chưa hoàn tất.",
				"<yellow>⚠ Giao dịch trước của bạn chưa xong. Vui lòng chờ một chút.</yellow>", total));
		}

		removeSimilar(player.getInventory(), sample, tradeAmount);

		int finalTradeAmount = tradeAmount;

		return economy.depositAsync(player, total)
			.thenCompose(paid -> onMainThread(() ->
				finishSell(player, shopItem, sample, amount, finalTradeAmount, total, Boolean.TRUE.equals(paid))))
			.whenComplete((ignored, failure) -> endTrade(playerId));
	}

	private ShopResult finishSell(Player player, ShopItem shopItem, ItemStack sample, int amount, int tradeAmount, double total, boolean paid) {
		if (!paid) {
			give(player.getInventory(), sample, tradeAmount);

			return fail("SELL", player, shopItem, tradeAmount, "Không thể cộng tiền vào ví người chơi.", "<red>❌ Không thể cộng tiền vào ví của bạn.</red>", total);
		}

		if (!tradeLimitService.consumeSell(player.getUniqueId(), shopItem, tradeAmount)) {
			refundReverse("SELL", player, total);
			give(player.getInventory(), sample, tradeAmount);

			return fail("SELL", player, shopItem, tradeAmount, "Đã đạt giới hạn bán trong ngày.", "<red>❌ Hạn mức bán vừa thay đổi, vui lòng thử lại.</red>", total);
		}

		logSuccess("SELL", player, shopItem, tradeAmount, total);

		if (tradeAmount < amount) {
			return ShopResult.ok("<yellow>⚠ Giới hạn trong ngày chỉ còn <white>" + tradeAmount + "</white>. Đã bán và nhận " + formatMoney(total) + ".</yellow>");
		}

		return ShopResult.ok("<green>✔ Bán thành công <white>" + tradeAmount + "</white> vật phẩm và nhận " + formatMoney(total) + ".</green>");
	}

	/**
	 * One kind of item in a bulk sale: how many were offered, and how many the daily
	 * limit actually allows.
	 */
	public record SellLot(ShopItem item, int offered, int sellable) {
		public double total() {
			return item.sellPrice() * sellable;
		}
	}

	/**
	 * What a sell inventory is worth as it stands: one lot per kind, plus what could not
	 * be sold, so the menu can say why before the player commits.
	 */
	public record SellPlan(List<SellLot> lots, double total, int sellableCount, int rejectedCount, int limitedCount) {
	}

	/**
	 * Price up everything sitting in the first [0, slotCount) slots of [inventory].
	 *
	 * Pure: it reads the inventory and the daily limits and changes neither, so the menu
	 * may call it on every click to redraw the running total.
	 */
	public SellPlan planInventorySale(Player player, Inventory inventory, int slotCount) {
		LinkedHashMap<String, Integer> offered = new LinkedHashMap<>();
		LinkedHashMap<String, ShopItem> known = new LinkedHashMap<>();
		int rejected = 0;

		for (int slot = 0; slot < slotCount && slot < inventory.getSize(); slot++) {
			ItemStack content = inventory.getItem(slot);
			if (content == null || content.getType().isAir()) {
				continue;
			}

			ShopItem shopItem = store.findBySimilarItem(content).orElse(null);
			if (shopItem == null || shopItem.sellPrice() <= 0D) {
				rejected += content.getAmount();
				continue;
			}

			offered.merge(shopItem.id(), content.getAmount(), Integer::sum);
			known.putIfAbsent(shopItem.id(), shopItem);
		}

		ArrayList<SellLot> lots = new ArrayList<>();
		double total = 0D;
		int sellableCount = 0;
		int limitedCount = 0;

		for (Map.Entry<String, Integer> entry : offered.entrySet()) {
			ShopItem shopItem = known.get(entry.getKey());
			int amount = entry.getValue();
			int sellable = capSellAmount(player, shopItem, amount);

			if (sellable < amount) {
				limitedCount++;
			}

			if (sellable <= 0) {
				lots.add(new SellLot(shopItem, amount, 0));
				continue;
			}

			SellLot lot = new SellLot(shopItem, amount, sellable);
			lots.add(lot);
			total += lot.total();
			sellableCount += sellable;
		}

		return new SellPlan(List.copyOf(lots), total, sellableCount, rejected, limitedCount);
	}

	/**
	 * Sell everything in a sell inventory in one go.
	 *
	 * The items leave the inventory before the wallet is asked and come back if it
	 * refuses, the same order a single sell uses; one deposit covers the whole lot, so a
	 * bulk sale is one round trip to a wallet that may live on the proxy rather than one
	 * per kind. Must be called on the main thread.
	 */
	public CompletableFuture<ShopResult> sellInventoryAsync(Player player, Inventory inventory, int slotCount) {
		SellPlan plan = planInventorySale(player, inventory, slotCount);

		if (plan.sellableCount() <= 0) {
			return completed(ShopResult.fail(plan.rejectedCount() > 0
				? "<red>❌ Không có vật phẩm nào trong khay bán được.</red>"
				: "<red>❌ Khay bán đang trống.</red>"));
		}

		UUID playerId = player.getUniqueId();

		if (!beginTrade(playerId)) {
			return completed(ShopResult.fail("<yellow>⚠ Giao dịch trước của bạn chưa xong. Vui lòng chờ một chút.</yellow>"));
		}

		List<SellLot> sold = plan.lots().stream().filter(lot -> lot.sellable() > 0).toList();

		for (SellLot lot : sold) {
			removeSimilar(inventory, sample(lot.item()), lot.sellable(), slotCount);
		}

		double total = plan.total();

		return economy.depositAsync(player, total)
			.thenCompose(paid -> onMainThread(() ->
				finishInventorySale(player, inventory, slotCount, sold, total, Boolean.TRUE.equals(paid))))
			.whenComplete((ignored, failure) -> endTrade(playerId));
	}

	private ShopResult finishInventorySale(Player player, Inventory inventory, int slotCount, List<SellLot> sold, double total, boolean paid) {
		if (!paid) {
			for (SellLot lot : sold) {
				giveBack(player, inventory, slotCount, sample(lot.item()), lot.sellable());
			}

			for (SellLot lot : sold) {
				logFailure("SELL", player, lot.item(), lot.sellable(), "Không thể cộng tiền vào ví người chơi.", lot.total());
			}

			return ShopResult.fail("<red>❌ Không thể cộng tiền vào ví của bạn. Vật phẩm đã được trả lại.</red>");
		}

		int soldCount = 0;
		double settled = 0D;
		double reversed = 0D;

		for (SellLot lot : sold) {
			if (!tradeLimitService.consumeSell(player.getUniqueId(), lot.item(), lot.sellable())) {
				// the cap was read under beginTrade, so this is close to unreachable; if it
				// does happen, that one kind goes back and its share of the money with it
				reversed += lot.total();
				giveBack(player, inventory, slotCount, sample(lot.item()), lot.sellable());
				logFailure("SELL", player, lot.item(), lot.sellable(), "Đã đạt giới hạn bán trong ngày.", lot.total());
				continue;
			}

			logSuccess("SELL", player, lot.item(), lot.sellable(), lot.total());
			soldCount += lot.sellable();
			settled += lot.total();
		}

		if (reversed > 0D) {
			refundReverse("SELL", player, reversed);
		}

		if (soldCount <= 0) {
			return ShopResult.fail("<red>❌ Hạn mức bán vừa thay đổi, vui lòng thử lại.</red>");
		}

		return ShopResult.ok("<green>✔ Đã bán <white>" + soldCount + "</white> vật phẩm ("
			+ sold.size() + " loại) và nhận " + formatMoney(settled) + ".</green>");
	}

	/** The entry's own stack; kept short-lived, the store owns the cached copy. */
	private ItemStack sample(ShopItem item) {
		return item.itemStack();
	}

	/**
	 * Put items back where they came from: into the sell inventory if it still has room,
	 * and into the player's own bag or at their feet if it does not, so a refused sale
	 * never eats the goods.
	 */
	private void giveBack(Player player, Inventory inventory, int slotCount, ItemStack sample, int amount) {
		int remaining = amount;
		int maxStack = sample.getMaxStackSize();

		// the player may have closed the tray while the wallet was answering, and putting
		// items into an inventory nobody is looking at is the same as destroying them
		boolean trayOpen = player.getOpenInventory().getTopInventory().equals(inventory);
		int trayLimit = trayOpen ? slotCount : 0;

		for (int slot = 0; slot < trayLimit && slot < inventory.getSize() && remaining > 0; slot++) {
			ItemStack content = inventory.getItem(slot);

			if (content == null || content.getType().isAir()) {
				int give = Math.min(maxStack, remaining);
				ItemStack stack = sample.clone();
				stack.setAmount(give);
				inventory.setItem(slot, stack);
				remaining -= give;
				continue;
			}

			if (!content.isSimilar(sample)) {
				continue;
			}

			int space = Math.max(0, maxStack - content.getAmount());
			if (space <= 0) {
				continue;
			}

			int give = Math.min(space, remaining);
			content.setAmount(content.getAmount() + give);
			inventory.setItem(slot, content);
			remaining -= give;
		}

		while (remaining > 0) {
			int give = Math.min(maxStack, remaining);
			ItemStack stack = sample.clone();
			stack.setAmount(give);

			for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
				player.getWorld().dropItemNaturally(player.getLocation(), leftover);
			}

			remaining -= give;
		}
	}

	/** Remove from the item area of a sell inventory only, never from its footer. */
	private void removeSimilar(Inventory inventory, ItemStack sample, int amount, int slotCount) {
		int remaining = amount;

		for (int slot = 0; slot < slotCount && slot < inventory.getSize() && remaining > 0; slot++) {
			ItemStack content = inventory.getItem(slot);
			if (content == null || content.getType().isAir() || !content.isSimilar(sample)) {
				continue;
			}

			if (content.getAmount() <= remaining) {
				remaining -= content.getAmount();
				inventory.clear(slot);
			} else {
				content.setAmount(content.getAmount() - remaining);
				inventory.setItem(slot, content);
				remaining = 0;
			}
		}
	}

	/** Sell everything of this kind the player is carrying, off the tick. */
	public CompletableFuture<ShopResult> sellAllSimilarAsync(Player player, ShopItem shopItem) {
		ItemStack sample = shopItem.itemStack();
		int owned = countSimilar(player.getInventory(), sample);

		if (owned <= 0) {
			return completed(ShopResult.fail("<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>"));
		}

		return sellAsync(player, shopItem, owned);
	}

	/**
	 * One trade per player at a time.
	 *
	 * Without this a player clicking twice inside one round trip passes every local
	 * check twice - their bag and their balance both still look untouched - and is
	 * charged twice for one lot of goods. The blocking version could not have this
	 * bug because the main thread was busy; making the trade async is what
	 * introduces it, so the guard arrives with it.
	 */
	private boolean beginTrade(UUID playerId) {
		return playerId != null && tradesInFlight.add(playerId);
	}

	private void endTrade(UUID playerId) {
		if (playerId != null) {
			tradesInFlight.remove(playerId);
		}
	}

	/** Give the money back after a buy that could not be completed. */
	private void refund(String action, Player player, double total) {
		economy.depositAsync(player, total).whenComplete((refunded, failure) -> {
			if (failure != null || !Boolean.TRUE.equals(refunded)) {
				logger.error("Không hoàn lại được " + formatMoney(total) + " cho " + player.getName()
					+ " sau khi " + action + " thất bại. Cần kiểm tra số dư thủ công.");
			}
		});
	}

	/** Take back money paid for a sell that could not be completed. */
	private void refundReverse(String action, Player player, double total) {
		economy.withdrawAsync(player, total).whenComplete((reversed, failure) -> {
			if (failure != null || !Boolean.TRUE.equals(reversed)) {
				logger.error("Không thu hồi được " + formatMoney(total) + " từ " + player.getName()
					+ " sau khi " + action + " thất bại. Cần kiểm tra số dư thủ công.");
			}
		});
	}

	/**
	 * Run on the main thread and answer with what it produced.
	 *
	 * Inline when already there, which is the whole Vault path: deferring it a tick
	 * would turn a trade that used to complete inside the click into one that
	 * finishes after it, for no gain.
	 */
	private <T> CompletableFuture<T> onMainThread(Supplier<T> work) {
		CompletableFuture<T> done = new CompletableFuture<>();

		if (plugin.getServer().isPrimaryThread()) {
			try {
				done.complete(work.get());
			} catch (RuntimeException failure) {
				done.completeExceptionally(failure);
			}

			return done;
		}

		plugin.getServer().getScheduler().runTask(plugin, () -> {
			try {
				done.complete(work.get());
			} catch (RuntimeException failure) {
				done.completeExceptionally(failure);
			}
		});

		return done;
	}

	private static <T> CompletableFuture<T> completed(T value) {
		return CompletableFuture.completedFuture(value);
	}

	public int countSimilar(Inventory inventory, ItemStack sample) {
		int total = 0;
		for (ItemStack content : inventory.getStorageContents()) {
			if (content == null || content.getType().isAir()) {
				continue;
			}

			if (content.isSimilar(sample)) {
				total += content.getAmount();
			}
		}

		return total;
	}

	private void give(Inventory inventory, ItemStack sample, int amount) {
		int remaining = amount;
		int maxStack = sample.getMaxStackSize();
		while (remaining > 0) {
			int giveAmount = Math.min(maxStack, remaining);
			ItemStack stack = sample.clone();
			stack.setAmount(giveAmount);
			inventory.addItem(stack);
			remaining -= giveAmount;
		}
	}

	private int maxAcceptable(Inventory inventory, ItemStack sample) {
		int maxStack = sample.getMaxStackSize();
		int space = 0;
		for (ItemStack content : inventory.getStorageContents()) {
			if (content == null || content.getType().isAir()) {
				space += maxStack;
				continue;
			}

			if (content.isSimilar(sample)) {
				space += Math.max(0, maxStack - content.getAmount());
			}
		}

		return space;
	}

	private void removeSimilar(Inventory inventory, ItemStack sample, int amount) {
		int remaining = amount;
		for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
			ItemStack content = inventory.getItem(slot);
			if (content == null || content.getType().isAir()) {
				continue;
			}

			if (!content.isSimilar(sample)) {
				continue;
			}

			if (content.getAmount() <= remaining) {
				remaining -= content.getAmount();
				inventory.clear(slot);
			} else {
				content.setAmount(content.getAmount() - remaining);
				inventory.setItem(slot, content);
				remaining = 0;
			}
		}
	}

	public ShopItemStore store() {
		return store;
	}

	private ShopResult fail(String action, Player player, ShopItem item, int amount, String reason, String message, double total) {
		recordTransaction(action, player, item, amount, total, false, reason);
		logFailure(action, player, item, amount, reason, total);
		return ShopResult.fail(message);
	}

	private void logSuccess(String action, Player player, ShopItem item, int amount, double total) {
		if (logger == null) {
			return;
		}

		recordTransaction(action, player, item, amount, total, true, "OK");

		logger.audit("TX " + Formatters.stripFormats(action)
			+ " | player=" + Formatters.stripFormats(player.getName())
			+ " (" + player.getUniqueId() + ")"
			+ " | item=" + Formatters.stripFormats(item.id())
			+ " | category=" + Formatters.stripFormats(item.category())
			+ " | amount=" + amount
			+ " | total=" + Formatters.stripFormats(formatMoney(total))
			+ " | result=SUCCESS");
	}

	private void logFailure(String action, Player player, ShopItem item, int amount, String reason, double total) {
		if (logger == null) {
			return;
		}

		logger.warn("TX " + Formatters.stripFormats(action)
			+ " | player=" + Formatters.stripFormats(player.getName())
			+ " (" + player.getUniqueId() + ")"
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
			List<ShopTransactionEntry> entries = transactionStore.findByPlayer(playerUuid, currentPage, pageSize);
			return new ShopHistoryPage(total, maxPage, currentPage, entries);
		});
	}

	public int transactionHistoryCount(UUID playerUuid) {
		return transactionStore.countByPlayer(playerUuid);
	}

	public List<ShopTransactionEntry> transactionHistory(UUID playerUuid, int page, int pageSize) {
		return transactionStore.findByPlayer(playerUuid, page, pageSize);
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

	private void recordTransaction(String action, Player player, ShopItem item, int amount, double total, boolean success, String reason) {
		if (plugin == null || transactionStore == null || player == null || item == null) {
			return;
		}

		double unitPrice = "BUY".equalsIgnoreCase(action) ? item.buyPrice() : item.sellPrice();
		ShopTransactionEntry entry = new ShopTransactionEntry(
			UUID.randomUUID().toString(),
			player.getUniqueId().toString(),
			player.getName(),
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

		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> transactionStore.insert(entry));
	}

	public record ShopHistoryPage(int total, int maxPage, int currentPage, List<ShopTransactionEntry> entries) {
	}
}

