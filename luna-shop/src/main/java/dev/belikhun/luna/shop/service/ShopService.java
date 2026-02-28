package dev.belikhun.luna.shop.service;

import dev.belikhun.luna.shop.economy.ShopEconomyService;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.store.ShopItemStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ShopService {
	private final ShopEconomyService economy;
	private final ShopItemStore store;

	public ShopService(ShopEconomyService economy, ShopItemStore store) {
		this.economy = economy;
		this.store = store;
	}

	public ShopEconomyService economy() {
		return economy;
	}

	public ShopResult buy(Player player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return ShopResult.fail("<red>❌ Số lượng mua không hợp lệ.</red>");
		}

		double total = shopItem.buyPrice() * amount;
		if (shopItem.buyPrice() <= 0D) {
			return ShopResult.fail("<red>❌ Vật phẩm này không thể mua.</red>");
		}

		if (!economy.has(player, total)) {
			return ShopResult.fail("<red>❌ Bạn không đủ tiền để mua.</red>");
		}

		ItemStack sample = shopItem.itemStack();
		int maxAcceptable = maxAcceptable(player.getInventory(), sample);
		if (maxAcceptable < amount) {
			return ShopResult.fail("<red>❌ Túi đồ không đủ chỗ chứa số lượng đã chọn.</red>");
		}

		if (!economy.withdraw(player, total)) {
			return ShopResult.fail("<red>❌ Không thể trừ tiền từ ví của bạn.</red>");
		}

		give(player.getInventory(), sample, amount);
		return ShopResult.ok("<green>✔ Mua thành công <white>" + amount + "</white> vật phẩm với giá <gold>" + economy.format(total) + "</gold>.</green>");
	}

	public ShopResult sell(Player player, ShopItem shopItem, int amount) {
		if (amount <= 0) {
			return ShopResult.fail("<red>❌ Số lượng bán không hợp lệ.</red>");
		}

		if (shopItem.sellPrice() <= 0D) {
			return ShopResult.fail("<red>❌ Vật phẩm này không thể bán.</red>");
		}

		ItemStack sample = shopItem.itemStack();
		int owned = countSimilar(player.getInventory(), sample);
		if (owned < amount) {
			return ShopResult.fail("<red>❌ Bạn không đủ vật phẩm để bán.</red>");
		}

		removeSimilar(player.getInventory(), sample, amount);
		double total = shopItem.sellPrice() * amount;
		if (!economy.deposit(player, total)) {
			give(player.getInventory(), sample, amount);
			return ShopResult.fail("<red>❌ Không thể cộng tiền vào ví của bạn.</red>");
		}

		return ShopResult.ok("<green>✔ Bán thành công <white>" + amount + "</white> vật phẩm và nhận <gold>" + economy.format(total) + "</gold>.</green>");
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
}