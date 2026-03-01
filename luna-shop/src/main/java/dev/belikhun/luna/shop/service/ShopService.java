package dev.belikhun.luna.shop.service;

import dev.belikhun.luna.core.LunaCore;
import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.shop.economy.ShopEconomyService;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.store.ShopItemStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ShopService {
	private final ShopEconomyService economy;
	private final ShopItemStore store;
	private final ShopTransactionStore transactionStore;
	private final String moneySymbol;
	private final boolean moneyGrouping;
	private final String moneyFormat;
	private final LunaLogger logger;

	public ShopService(ShopEconomyService economy, ShopItemStore store, ShopTransactionStore transactionStore, LunaLogger logger) {
		this.economy = economy;
		this.store = store;
		this.transactionStore = transactionStore;
		this.logger = logger;
		ConfigStore coreConfig = LunaCore.services().configStore();
		this.moneySymbol = coreConfig.get("strings.money.currencySymbol").asString("₫");
		this.moneyGrouping = coreConfig.get("strings.money.grouping").asBoolean(true);
		this.moneyFormat = coreConfig.get("strings.money.format").asString("{amount}{symbol}");
	}

	public ShopEconomyService economy() {
		return economy;
	}

	public String formatMoney(double amount) {
		return Formatters.money(amount, moneySymbol, moneyGrouping, moneyFormat);
	}

	public ShopResult buy(Player player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return fail("BUY", player, shopItem, amount, "Số lượng mua không hợp lệ.", "<red>❌ Số lượng mua không hợp lệ.</red>", 0D);
		}

		double total = shopItem.buyPrice() * amount;
		if (shopItem.buyPrice() <= 0D) {
			return fail("BUY", player, shopItem, amount, "Vật phẩm không thể mua.", "<red>❌ Vật phẩm này không thể mua.</red>", total);
		}

		if (!economy.has(player, total)) {
			return fail("BUY", player, shopItem, amount, "Không đủ tiền để mua.", "<red>❌ Bạn không đủ tiền để mua.</red>", total);
		}

		ItemStack sample = shopItem.itemStack();
		int maxAcceptable = maxAcceptable(player.getInventory(), sample);
		if (maxAcceptable < amount) {
			return fail("BUY", player, shopItem, amount, "Túi đồ không đủ chỗ.", "<red>❌ Túi đồ không đủ chỗ chứa số lượng đã chọn.</red>", total);
		}

		if (!economy.withdraw(player, total)) {
			return fail("BUY", player, shopItem, amount, "Không thể trừ tiền từ ví người chơi.", "<red>❌ Không thể trừ tiền từ ví của bạn.</red>", total);
		}

		give(player.getInventory(), sample, amount);
		logSuccess("BUY", player, shopItem, amount, total);
		return ShopResult.ok("<green>✔ Mua thành công <white>" + amount + "</white> vật phẩm với giá " + formatMoney(total) + "<green>.</green>");
	}

	public ShopResult sell(Player player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return fail("SELL", player, shopItem, amount, "Số lượng bán không hợp lệ.", "<red>❌ Số lượng bán không hợp lệ.</red>", 0D);
		}

		if (shopItem.sellPrice() <= 0D) {
			return fail("SELL", player, shopItem, amount, "Vật phẩm không thể bán.", "<red>❌ Vật phẩm này không thể bán.</red>", 0D);
		}

		ItemStack sample = shopItem.itemStack();
		int owned = countSimilar(player.getInventory(), sample);
		if (owned < amount) {
			return fail("SELL", player, shopItem, amount, "Không đủ vật phẩm để bán.", "<red>❌ Bạn không đủ vật phẩm để bán.</red>", shopItem.sellPrice() * amount);
		}

		removeSimilar(player.getInventory(), sample, amount);
		double total = shopItem.sellPrice() * amount;
		if (!economy.deposit(player, total)) {
			give(player.getInventory(), sample, amount);
			return fail("SELL", player, shopItem, amount, "Không thể cộng tiền vào ví người chơi.", "<red>❌ Không thể cộng tiền vào ví của bạn.</red>", total);
		}

		logSuccess("SELL", player, shopItem, amount, total);
		return ShopResult.ok("<green>✔ Bán thành công <white>" + amount + "</white> vật phẩm và nhận " + formatMoney(total) + "<green>.</green>");
	}

	public ShopResult sellAllSimilar(Player player, ShopItem shopItem) {
		ItemStack sample = shopItem.itemStack();
		int owned = countSimilar(player.getInventory(), sample);
		if (owned <= 0) {
			return ShopResult.fail("<red>❌ Bạn không có vật phẩm tương tự để bán nhanh.</red>");
		}

		return sell(player, shopItem, owned);
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

		double balanceAfter = economy.balance(player);
		logger.audit("TX " + action
			+ " | player=" + player.getName()
			+ " (" + player.getUniqueId() + ")"
			+ " | item=" + item.id()
			+ " | category=" + item.category()
			+ " | amount=" + amount
			+ " | total=" + formatMoney(total)
			+ " | balanceAfter=" + formatMoney(balanceAfter)
			+ " | result=SUCCESS");
	}

	private void logFailure(String action, Player player, ShopItem item, int amount, String reason, double total) {
		if (logger == null) {
			return;
		}

		double balanceNow = economy.balance(player);
		logger.warn("TX " + action
			+ " | player=" + player.getName()
			+ " (" + player.getUniqueId() + ")"
			+ " | item=" + item.id()
			+ " | category=" + item.category()
			+ " | amount=" + amount
			+ " | total=" + formatMoney(total)
			+ " | balanceNow=" + formatMoney(balanceNow)
			+ " | result=FAILED"
			+ " | reason=" + reason);
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
		if (transactionStore == null || player == null || item == null) {
			return;
		}

		double unitPrice = "BUY".equalsIgnoreCase(action) ? item.buyPrice() : item.sellPrice();
		transactionStore.insert(new ShopTransactionEntry(
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
		));
	}
}