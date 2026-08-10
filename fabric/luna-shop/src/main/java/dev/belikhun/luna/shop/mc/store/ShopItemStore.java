package dev.belikhun.luna.shop.mc.store;

import dev.belikhun.luna.core.mc.compat.ItemDecor;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.shop.api.ShopItemIds;
import dev.belikhun.luna.shop.mc.model.ShopCategory;
import dev.belikhun.luna.shop.mc.model.ShopItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * items.yml: every category and every item the shop sells.
 *
 * The file is the source of truth and is rewritten whole on each change, which
 * is what keeps the exported {@code item-id} / {@code item-name} / {@code
 * item-lore} fields honest - they exist only so an operator can read the file,
 * and are re-derived from {@code item-data} rather than trusted on load.
 */
public final class ShopItemStore {
	private static final String FILE_NAME = "items.yml";
	private static final String CATEGORIES = "categories";
	private static final String ITEMS = "shop-items";

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final Path file;
	private final ConcurrentMap<String, ShopItem> items;
	private final ConcurrentMap<String, ShopCategory> categories;

	public ShopItemStore(MinecraftServer server, Path configDirectory, LunaLogger logger) {
		this.server = server;
		this.logger = logger;
		this.file = configDirectory.resolve(FILE_NAME);
		this.items = new ConcurrentHashMap<>();
		this.categories = new ConcurrentHashMap<>();
	}

	public void load() {
		items.clear();
		categories.clear();

		YamlConfigFile configuration = YamlConfigFile.loadOrEmpty(file);

		for (Map.Entry<String, Object> entry : configuration.section(CATEGORIES).entrySet()) {
			Map<String, Object> node = ConfigValues.map(entry.getValue());
			String iconData = ConfigValues.string(node, "icon-data", "");

			if (iconData.isBlank()) {
				continue;
			}

			String id = ShopItemIds.normalizeCategory(ConfigValues.string(node, "id", entry.getKey()));
			categories.put(id, new ShopCategory(id, iconData, ConfigValues.string(node, "display-name", "")));
		}

		for (Map.Entry<String, Object> entry : configuration.section(ITEMS).entrySet()) {
			Map<String, Object> node = ConfigValues.map(entry.getValue());
			String itemData = ConfigValues.string(node, "item-data", "");

			if (itemData.isBlank()) {
				continue;
			}

			String id = ShopItemIds.normalizeId(ConfigValues.string(node, "id", entry.getKey()));
			items.put(id, new ShopItem(
				id,
				ConfigValues.string(node, "category", "general"),
				ConfigValues.doubleValue(node, "buy-price", 0D),
				ConfigValues.doubleValue(node, "sell-price", 0D),
				Math.max(0, ConfigValues.intValue(node, "buy-trade-limit", 0)),
				Math.max(0, ConfigValues.intValue(node, "sell-trade-limit", 0)),
				itemData,
				ConfigValues.longValue(node, "added-date", System.currentTimeMillis())
			));
		}

		ensureCategoryFallbacks();
	}

	public synchronized void save() {
		YamlConfigFile configuration = YamlConfigFile.empty(file);

		for (ShopCategory category : allCategories()) {
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("id", category.id());
			node.put("icon-data", category.iconData());
			node.put("display-name", category.displayName());
			configuration.set(CATEGORIES + "." + category.id(), node);
		}

		for (ShopItem item : all()) {
			ItemStack stack = item.itemStack(server);
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("id", item.id());
			node.put("category", item.category());
			node.put("buy-price", item.buyPrice());
			node.put("sell-price", item.sellPrice());
			node.put("buy-trade-limit", item.buyTradeLimit());
			node.put("sell-trade-limit", item.sellTradeLimit());
			node.put("added-date", item.addedDate());
			node.put("item-id", itemIdForExport(stack));
			node.put("item-name", itemNameForExport(stack));
			node.put("item-lore", itemLoreForExport(stack));
			node.put("item-data", item.itemData());
			configuration.set(ITEMS + "." + item.id(), node);
		}

		try {
			configuration.save();
		} catch (RuntimeException exception) {
			logger.error("Không thể lưu items.yml: " + exception.getMessage(), exception);
		}
	}

	public List<ShopItem> all() {
		ArrayList<ShopItem> list = new ArrayList<>(items.values());
		list.sort(Comparator.comparing(ShopItem::id));
		return list;
	}

	public Optional<ShopItem> find(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}

		return Optional.ofNullable(items.get(ShopItemIds.normalizeId(id)));
	}

	/**
	 * The shop entry for an item a player is holding.
	 *
	 * Matching is by item and components, which is what makes a renamed sword a
	 * different product from a plain one - the same rule Bukkit's isSimilar uses
	 * on the Paper side.
	 */
	public Optional<ShopItem> findBySimilarItem(ItemStack itemStack) {
		if (itemStack == null || itemStack.isEmpty()) {
			return Optional.empty();
		}

		ItemStack normalized = itemStack.copyWithCount(1);

		for (ShopItem item : items.values()) {
			if (ItemDecor.sameItemAndData(item.itemStack(server), normalized)) {
				return Optional.of(item);
			}
		}

		return Optional.empty();
	}

	public boolean remove(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}

		if (items.remove(ShopItemIds.normalizeId(id)) == null) {
			return false;
		}

		save();
		return true;
	}

	public void upsert(ShopItem item) {
		items.put(item.id(), item);
		save();
	}

	public Set<String> categories() {
		HashSet<String> values = new HashSet<>();

		for (ShopCategory category : categories.values()) {
			values.add(category.id());
		}

		for (ShopItem item : items.values()) {
			values.add(item.category());
		}

		return values;
	}

	public List<ShopCategory> allCategories() {
		ArrayList<ShopCategory> values = new ArrayList<>(categories.values());
		values.sort(Comparator.comparing(ShopCategory::id));
		return values;
	}

	public Optional<ShopCategory> findCategory(String categoryId) {
		if (categoryId == null || categoryId.isBlank()) {
			return Optional.empty();
		}

		return Optional.ofNullable(categories.get(ShopItemIds.normalizeCategory(categoryId)));
	}

	public void upsertCategory(ShopCategory category) {
		categories.put(category.id(), category);
		save();
	}

	public void upsertCategoryIcon(String categoryId, ItemStack icon) {
		String normalizedId = ShopItemIds.normalizeCategory(categoryId);
		ShopCategory existing = categories.get(normalizedId);
		ShopCategory category = ShopCategory.fromIcon(server, categoryId, icon);

		if (existing != null && existing.hasDisplayName()) {
			category = category.withDisplayName(existing.displayName());
		}

		categories.put(category.id(), category);
		save();
	}

	public boolean updateCategoryDisplayName(String categoryId, String displayName) {
		String normalizedId = ShopItemIds.normalizeCategory(categoryId);
		ShopCategory category = categories.get(normalizedId);

		if (category == null) {
			return false;
		}

		categories.put(normalizedId, category.withDisplayName(displayName));
		save();
		return true;
	}

	public boolean renameCategory(String oldId, String newId) {
		String normalizedOld = ShopItemIds.normalizeCategory(oldId);
		String normalizedNew = ShopItemIds.normalizeCategory(newId);
		ShopCategory old = categories.get(normalizedOld);

		if (old == null) {
			return false;
		}

		categories.remove(normalizedOld);
		categories.put(normalizedNew, new ShopCategory(normalizedNew, old.iconData(), old.displayName()));

		for (Map.Entry<String, ShopItem> entry : items.entrySet()) {
			if (entry.getValue().category().equalsIgnoreCase(normalizedOld)) {
				entry.setValue(entry.getValue().withCategory(normalizedNew));
			}
		}

		save();
		return true;
	}

	/**
	 * Delete a category, moving anything in it to {@code moveTo}.
	 *
	 * A category that still holds items and has nowhere to move them is refused
	 * and put back, because the alternative is items that no screen can reach.
	 */
	public boolean deleteCategory(String id, String moveTo) {
		String normalizedId = ShopItemIds.normalizeCategory(id);
		ShopCategory removed = categories.remove(normalizedId);

		if (removed == null) {
			return false;
		}

		long affected = items.values().stream()
			.filter(item -> item.category().equalsIgnoreCase(normalizedId))
			.count();

		if (affected > 0) {
			if (moveTo == null || moveTo.isBlank()) {
				categories.put(normalizedId, removed);
				return false;
			}

			String target = ShopItemIds.normalizeCategory(moveTo);
			categories.putIfAbsent(target, ShopCategory.defaultCategory(server, target));

			for (Map.Entry<String, ShopItem> entry : items.entrySet()) {
				if (entry.getValue().category().equalsIgnoreCase(normalizedId)) {
					entry.setValue(entry.getValue().withCategory(target));
				}
			}
		}

		save();
		return true;
	}

	public List<ShopItem> byCategory(String category) {
		String normalized = ShopItemIds.normalizeCategory(category);
		return new ArrayList<>(all().stream()
			.filter(item -> item.category().equalsIgnoreCase(normalized))
			.toList());
	}

	public List<ShopItem> search(String keyword) {
		String normalized = keyword == null ? "" : keyword.trim().toLowerCase();

		if (normalized.isBlank()) {
			return all();
		}

		return new ArrayList<>(all().stream().filter(item -> {
			if (item.id().toLowerCase().contains(normalized)) {
				return true;
			}

			if (item.category().toLowerCase().contains(normalized)) {
				return true;
			}

			return nameForSearch(item.itemStack(server)).toLowerCase().contains(normalized);
		}).toList());
	}

	public List<ShopItem> searchInCategory(String category, String keyword) {
		String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();

		return new ArrayList<>(byCategory(category).stream().filter(item -> {
			if (normalizedKeyword.isBlank()) {
				return true;
			}

			if (item.id().toLowerCase().contains(normalizedKeyword)) {
				return true;
			}

			return nameForSearch(item.itemStack(server)).toLowerCase().contains(normalizedKeyword);
		}).toList());
	}

	/** The name a player would see, for sorting and searching. */
	public String displayNameOf(ItemStack stack) {
		return nameForSearch(stack);
	}

	private String nameForSearch(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}

		Component customName = ItemDecor.readName(stack);

		if (customName != null) {
			return customName.getString();
		}

		return stack.getHoverName().getString();
	}

	/**
	 * The registry id, for a human reading items.yml.
	 *
	 * Item.toString() is the registry key, and is the one way to it that reads the
	 * same on both game lines - ItemStack.getItemHolder() is gone from 26.x, and
	 * the holder's own key spells its path differently there.
	 */
	private String itemIdForExport(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "minecraft:air";
		}

		return stack.getItem().toString();
	}

	private String itemNameForExport(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}

		return nameForSearch(stack);
	}

	private List<String> itemLoreForExport(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}

		List<Component> lore = ItemDecor.readLore(stack);

		if (lore.isEmpty()) {
			return List.of();
		}

		ArrayList<String> lines = new ArrayList<>();

		for (Component line : lore) {
			lines.add(line.getString());
		}

		return lines;
	}

	private void ensureCategoryFallbacks() {
		for (ShopItem item : items.values()) {
			categories.putIfAbsent(item.category(), ShopCategory.defaultCategory(server, item.category()));
		}
	}
}
