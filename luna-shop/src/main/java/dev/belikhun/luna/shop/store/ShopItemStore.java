package dev.belikhun.luna.shop.store;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.shop.model.ShopCategory;
import dev.belikhun.luna.shop.model.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ShopItemStore {
	private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final File file;
	private final ConcurrentMap<String, ShopItem> items;
	private final ConcurrentMap<String, ShopCategory> categories;

	public ShopItemStore(JavaPlugin plugin, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger;
		this.file = new File(this.plugin.getDataFolder(), "items.yml");
		this.items = new ConcurrentHashMap<>();
		this.categories = new ConcurrentHashMap<>();
	}

	public void load() {
		items.clear();
		categories.clear();
		YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);

		ConfigurationSection categorySection = configuration.getConfigurationSection("categories");
		if (categorySection != null) {
			for (String key : categorySection.getKeys(false)) {
				String root = "categories." + key;
				String id = configuration.getString(root + ".id", key);
				String iconData = configuration.getString(root + ".icon-data", "");
				String displayName = configuration.getString(root + ".display-name", "");
				if (iconData == null || iconData.isBlank()) {
					continue;
				}

				String normalizedId = ShopItem.normalizeCategory(id);
				categories.put(normalizedId, new ShopCategory(normalizedId, iconData, displayName));
			}
		}

		ConfigurationSection section = configuration.getConfigurationSection("shop-items");
		if (section == null) {
			ensureCategoryFallbacks();
			return;
		}

		for (String key : section.getKeys(false)) {
			String root = "shop-items." + key;
			String id = configuration.getString(root + ".id", key);
			String category = configuration.getString(root + ".category", "general");
			double buyPrice = configuration.getDouble(root + ".buy-price", 0D);
			double sellPrice = configuration.getDouble(root + ".sell-price", 0D);
			long addedDate = configuration.getLong(root + ".added-date", System.currentTimeMillis());
			String itemData = configuration.getString(root + ".item-data", "");
			if (itemData == null || itemData.isBlank()) {
				continue;
			}

			String normalizedId = ShopItem.normalizeId(id);
			items.put(normalizedId, new ShopItem(normalizedId, category, buyPrice, sellPrice, itemData, addedDate));
		}

		ensureCategoryFallbacks();
	}

	public synchronized void save() {
		YamlConfiguration configuration = new YamlConfiguration();
		for (ShopCategory category : allCategories()) {
			String root = "categories." + category.id();
			configuration.set(root + ".id", category.id());
			configuration.set(root + ".icon-data", category.iconData());
			configuration.set(root + ".display-name", category.displayName());
		}

		for (ShopItem item : all()) {
			String root = "shop-items." + item.id();
			ItemStack stack = item.itemStack();
			configuration.set(root + ".id", item.id());
			configuration.set(root + ".category", item.category());
			configuration.set(root + ".buy-price", item.buyPrice());
			configuration.set(root + ".sell-price", item.sellPrice());
			configuration.set(root + ".added-date", item.addedDate());
			configuration.set(root + ".item-id", stack.getType().getKey().asString());
			configuration.set(root + ".item-name", itemNameForExport(stack));
			configuration.set(root + ".item-lore", itemLoreForExport(stack));
			configuration.set(root + ".item-data", item.itemData());
		}

		try {
			configuration.save(file);
		} catch (IOException exception) {
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

		return Optional.ofNullable(items.get(ShopItem.normalizeId(id)));
	}

	public Optional<ShopItem> findBySimilarItem(ItemStack itemStack) {
		if (itemStack == null || itemStack.getType().isAir()) {
			return Optional.empty();
		}

		ItemStack normalized = itemStack.clone();
		normalized.setAmount(1);
		for (ShopItem item : items.values()) {
			if (item.itemStack().isSimilar(normalized)) {
				return Optional.of(item);
			}
		}

		return Optional.empty();
	}

	public boolean remove(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}

		ShopItem removed = items.remove(ShopItem.normalizeId(id));
		if (removed != null) {
			save();
			return true;
		}

		return false;
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

		return Optional.ofNullable(categories.get(ShopItem.normalizeCategory(categoryId)));
	}

	public void upsertCategory(ShopCategory category) {
		categories.put(category.id(), category);
		save();
	}

	public void upsertCategoryIcon(String categoryId, ItemStack icon) {
		String normalizedId = ShopItem.normalizeCategory(categoryId);
		ShopCategory existing = categories.get(normalizedId);
		ShopCategory category = ShopCategory.fromIcon(categoryId, icon);
		if (existing != null && existing.hasDisplayName()) {
			category = category.withDisplayName(existing.displayName());
		}
		categories.put(category.id(), category);
		save();
	}

	public boolean updateCategoryDisplayName(String categoryId, String displayName) {
		String normalizedId = ShopItem.normalizeCategory(categoryId);
		ShopCategory category = categories.get(normalizedId);
		if (category == null) {
			return false;
		}

		categories.put(normalizedId, category.withDisplayName(displayName));
		save();
		return true;
	}

	public boolean renameCategory(String oldId, String newId) {
		String normalizedOld = ShopItem.normalizeCategory(oldId);
		String normalizedNew = ShopItem.normalizeCategory(newId);
		ShopCategory old = categories.get(normalizedOld);
		if (old == null) {
			return false;
		}

		categories.remove(normalizedOld);
		categories.put(normalizedNew, new ShopCategory(normalizedNew, old.iconData(), old.displayName()));
		for (Map.Entry<String, ShopItem> entry : items.entrySet()) {
			ShopItem item = entry.getValue();
			if (!item.category().equalsIgnoreCase(normalizedOld)) {
				continue;
			}

			entry.setValue(new ShopItem(item.id(), normalizedNew, item.buyPrice(), item.sellPrice(), item.itemData(), item.addedDate()));
		}

		save();
		return true;
	}

	public boolean deleteCategory(String id, String moveTo) {
		String normalizedId = ShopItem.normalizeCategory(id);
		ShopCategory removed = categories.remove(normalizedId);
		if (removed == null) {
			return false;
		}

		int affected = 0;
		for (ShopItem item : items.values()) {
			if (item.category().equalsIgnoreCase(normalizedId)) {
				affected++;
			}
		}

		if (affected > 0) {
			if (moveTo == null || moveTo.isBlank()) {
				categories.put(normalizedId, removed);
				return false;
			}

			String target = ShopItem.normalizeCategory(moveTo);
			categories.putIfAbsent(target, ShopCategory.defaultCategory(target));
			for (Map.Entry<String, ShopItem> entry : items.entrySet()) {
				ShopItem item = entry.getValue();
				if (!item.category().equalsIgnoreCase(normalizedId)) {
					continue;
				}

				entry.setValue(new ShopItem(item.id(), target, item.buyPrice(), item.sellPrice(), item.itemData(), item.addedDate()));
			}
		}

		save();
		return true;
	}

	public List<ShopItem> byCategory(String category) {
		String normalized = ShopItem.normalizeCategory(category);
		return new ArrayList<>(all().stream().filter(item -> item.category().equalsIgnoreCase(normalized)).toList());
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

			String displayName = nameForSearch(item.itemStack());
			return displayName.toLowerCase().contains(normalized);
		}).toList());
	}

	public List<ShopItem> searchInCategory(String category, String keyword) {
		String normalizedCategory = ShopItem.normalizeCategory(category);
		String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
		return new ArrayList<>(byCategory(normalizedCategory).stream().filter(item -> {
			if (normalizedKeyword.isBlank()) {
				return true;
			}

			if (item.id().toLowerCase().contains(normalizedKeyword)) {
				return true;
			}

			String displayName = nameForSearch(item.itemStack());
			return displayName.toLowerCase().contains(normalizedKeyword);
		}).toList());
	}

	private String nameForSearch(ItemStack stack) {
		if (!stack.hasItemMeta()) {
			return stack.getType().name();
		}

		ItemMeta meta = stack.getItemMeta();
		if (meta.hasCustomName() && meta.customName() != null) {
			return PLAIN_TEXT.serialize(meta.customName());
		}

		return stack.getType().name();
	}

	private String itemNameForExport(ItemStack stack) {
		if (!stack.hasItemMeta()) {
			return stack.getType().getKey().asString();
		}

		ItemMeta meta = stack.getItemMeta();
		if (meta.hasCustomName() && meta.customName() != null) {
			return PLAIN_TEXT.serialize(meta.customName());
		}

		return stack.getType().getKey().asString();
	}

	private List<String> itemLoreForExport(ItemStack stack) {
		if (!stack.hasItemMeta()) {
			return List.of();
		}

		ItemMeta meta = stack.getItemMeta();
		List<Component> lore = meta.lore();
		if (lore == null || lore.isEmpty()) {
			return List.of();
		}

		ArrayList<String> lines = new ArrayList<>();
		for (Component line : lore) {
			lines.add(PLAIN_TEXT.serialize(line));
		}

		return lines;
	}

	private void ensureCategoryFallbacks() {
		for (ShopItem item : items.values()) {
			categories.putIfAbsent(item.category(), ShopCategory.defaultCategory(item.category()));
		}
	}
}
