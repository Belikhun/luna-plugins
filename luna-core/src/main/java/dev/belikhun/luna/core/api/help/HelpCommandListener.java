package dev.belikhun.luna.core.api.help;

import dev.belikhun.luna.core.LunaCoreServices;
import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HelpCommandListener implements Listener {
	private static final int CONTENT_SIZE = 45;

	private final LunaCoreServices services;
	private final GuiManager guiManager;
	private final MiniMessage miniMessage;
	private final PlainTextComponentSerializer plainText;
	private final Set<UUID> waitingSearchInput;
	private final Map<UUID, String> searchQueries;

	public HelpCommandListener(LunaCoreServices services) {
		this.services = services;
		this.guiManager = new GuiManager();
		this.miniMessage = MiniMessage.miniMessage();
		this.plainText = PlainTextComponentSerializer.plainText();
		this.waitingSearchInput = ConcurrentHashMap.newKeySet();
		this.searchQueries = new ConcurrentHashMap<>();
		services.plugin().getServer().getPluginManager().registerEvents(guiManager, services.plugin());
		registerDefaultEntries();
	}

	public void openHelp(Player player) {
		openFrontPage(player, 0);
	}

	public boolean openHelp(Player player, String target) {
		String normalized = target == null ? "" : target.trim();
		if (normalized.isBlank()) {
			openFrontPage(player, 0);
			return true;
		}

		if (services.helpRegistry().findVisibleCategory(player, normalized).map(category -> {
			openCategoryPage(player, category, 0);
			return true;
		}).orElse(false)) {
			return true;
		}

		if (services.helpRegistry().findVisibleEntry(player, normalized).map(entry -> {
			openSearchResults(player, entry.command(), 0);
			return true;
		}).orElse(false)) {
			return true;
		}

		openSearchResults(player, normalized, 0);
		return false;
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onSearchChat(AsyncChatEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (!waitingSearchInput.contains(uuid)) {
			return;
		}

		event.setCancelled(true);
		String query = plainText.serialize(event.message()).trim();
		waitingSearchInput.remove(uuid);
		if (query.isBlank() || query.equalsIgnoreCase("huy") || query.equalsIgnoreCase("cancel")) {
			searchQueries.remove(uuid);
			services.plugin().getServer().getScheduler().runTask(services.plugin(), () -> {
				player.sendMessage(miniMessage.deserialize("<yellow>ℹ Đã hủy tìm kiếm.</yellow>"));
				openFrontPage(player, 0);
			});
			return;
		}

		searchQueries.put(uuid, query);
		services.plugin().getServer().getScheduler().runTask(services.plugin(), () -> {
			player.sendMessage(miniMessage.deserialize("<green>✔ Đang hiển thị kết quả cho:</green> <white>" + query));
			openSearchResults(player, query, 0);
		});
	}

	private void openFrontPage(Player player, int page) {
		int size = normalizeSize(services.configStore().get("help.gui.size").asInt(54));
		GuiView gui = new GuiView(size, miniMessage.deserialize("<gradient:#f8c630:#f39c12>📚 Trung Tâm Trợ Giúp Luna</gradient>"));
		guiManager.track(gui);

		String query = searchQueries.get(player.getUniqueId());
		List<HelpCategory> categories = services.helpRegistry().visibleCategories(player);
		if (query != null && !query.isBlank()) {
			List<HelpCategory> filtered = new ArrayList<>();
			for (HelpCategory category : categories) {
				List<HelpEntry> entries = services.helpRegistry().visibleEntriesByCategory(player, category.id());
				if (entries.stream().anyMatch(entry -> matchesQuery(entry, query)) || category.title().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
					filtered.add(category);
				}
			}
			categories = filtered;
		}

		int maxPage = maxPage(categories.size());
		int currentPage = clampPage(page, maxPage);
		int start = currentPage * CONTENT_SIZE;
		int end = Math.min(categories.size(), start + CONTENT_SIZE);
		for (int index = start; index < end; index++) {
			HelpCategory category = categories.get(index);
			int slot = index - start;
			gui.setItem(slot, categoryItem(player, category), (clicker, event, view) -> openCategoryPage(clicker, category, 0));
		}

		fillFooter(gui);
		if (currentPage > 0) {
			gui.setItem(45, navItem(Material.ARROW, "<yellow>⬅ Trang trước", List.of("<gray>Quay về trang danh mục trước.")), (clicker, event, view) -> openFrontPage(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			gui.setItem(53, navItem(Material.ARROW, "<yellow>Trang sau ➡", List.of("<gray>Xem thêm danh mục lệnh.")), (clicker, event, view) -> openFrontPage(clicker, currentPage + 1));
		}

		gui.setItem(49, navItem(Material.COMPASS, "<aqua>🔍 Tìm kiếm lệnh", List.of("<gray>Nhấn để nhập từ khóa trên chat.", "<gray>Gõ <white>huy</white> để hủy.")), (clicker, event, view) -> beginSearch(clicker));
		if (query != null && !query.isBlank()) {
			gui.setItem(48, navItem(Material.BOOK, "<green>Kết quả tìm kiếm", List.of("<gray>Từ khóa hiện tại: <white>" + query, "<gray>Nhấn để mở danh sách kết quả.")), (clicker, event, view) -> openSearchResults(clicker, query, 0));
			gui.setItem(50, navItem(Material.BARRIER, "<red>Xóa bộ lọc", List.of("<gray>Hiển thị lại toàn bộ danh mục.")), (clicker, event, view) -> {
				searchQueries.remove(clicker.getUniqueId());
				openFrontPage(clicker, 0);
			});
		}
		gui.setItem(52, navItem(Material.OAK_DOOR, "<red>Đóng", List.of("<gray>Đóng giao diện trợ giúp.")), (clicker, event, view) -> clicker.closeInventory());

		player.openInventory(gui.getInventory());
	}

	private void openCategoryPage(Player player, HelpCategory category, int page) {
		int size = normalizeSize(services.configStore().get("help.gui.size").asInt(54));
		GuiView gui = new GuiView(size, miniMessage.deserialize("<gold>📁 " + category.title()));
		guiManager.track(gui);

		List<HelpEntry> entries = services.helpRegistry().visibleEntriesByCategory(player, category.id());
		entries.sort(Comparator.comparing(entry -> entry.command().toLowerCase(Locale.ROOT)));
		int maxPage = maxPage(entries.size());
		int currentPage = clampPage(page, maxPage);
		int start = currentPage * CONTENT_SIZE;
		int end = Math.min(entries.size(), start + CONTENT_SIZE);
		for (int index = start; index < end; index++) {
			HelpEntry entry = entries.get(index);
			int slot = index - start;
			gui.setItem(slot, commandItem(entry));
		}

		fillFooter(gui);
		if (currentPage > 0) {
			gui.setItem(45, navItem(Material.ARROW, "<yellow>⬅ Trang trước", List.of("<gray>Quay về trang lệnh trước.")), (clicker, event, view) -> openCategoryPage(clicker, category, currentPage - 1));
		}
		if (currentPage < maxPage) {
			gui.setItem(53, navItem(Material.ARROW, "<yellow>Trang sau ➡", List.of("<gray>Xem thêm lệnh trong danh mục.")), (clicker, event, view) -> openCategoryPage(clicker, category, currentPage + 1));
		}

		gui.setItem(49, navItem(Material.CHEST, "<aqua>Quay lại danh mục", List.of("<gray>Trở về trang chính trợ giúp.")), (clicker, event, view) -> openFrontPage(clicker, 0));
		gui.setItem(52, navItem(Material.OAK_DOOR, "<red>Đóng", List.of("<gray>Đóng giao diện trợ giúp.")), (clicker, event, view) -> clicker.closeInventory());

		player.openInventory(gui.getInventory());
	}

	private void openSearchResults(Player player, String query, int page) {
		int size = normalizeSize(services.configStore().get("help.gui.size").asInt(54));
		GuiView gui = new GuiView(size, miniMessage.deserialize("<aqua>🔎 Kết Quả: <white>" + query));
		guiManager.track(gui);

		List<HelpEntry> entries = services.helpRegistry().search(player, query);
		entries.sort(Comparator.comparing(entry -> entry.command().toLowerCase(Locale.ROOT)));
		int maxPage = maxPage(entries.size());
		int currentPage = clampPage(page, maxPage);
		int start = currentPage * CONTENT_SIZE;
		int end = Math.min(entries.size(), start + CONTENT_SIZE);
		for (int index = start; index < end; index++) {
			HelpEntry entry = entries.get(index);
			int slot = index - start;
			gui.setItem(slot, commandItem(entry));
		}

		fillFooter(gui);
		if (currentPage > 0) {
			gui.setItem(45, navItem(Material.ARROW, "<yellow>⬅ Trang trước", List.of("<gray>Quay về trang kết quả trước.")), (clicker, event, view) -> openSearchResults(clicker, query, currentPage - 1));
		}
		if (currentPage < maxPage) {
			gui.setItem(53, navItem(Material.ARROW, "<yellow>Trang sau ➡", List.of("<gray>Xem thêm kết quả.")), (clicker, event, view) -> openSearchResults(clicker, query, currentPage + 1));
		}

		gui.setItem(49, navItem(Material.COMPASS, "<aqua>Nhập từ khóa mới", List.of("<gray>Tìm kiếm lại từ đầu.")), (clicker, event, view) -> beginSearch(clicker));
		gui.setItem(50, navItem(Material.BARRIER, "<red>Xóa tìm kiếm", List.of("<gray>Trở về trang danh mục.")), (clicker, event, view) -> {
			searchQueries.remove(clicker.getUniqueId());
			openFrontPage(clicker, 0);
		});
		gui.setItem(52, navItem(Material.OAK_DOOR, "<red>Đóng", List.of("<gray>Đóng giao diện trợ giúp.")), (clicker, event, view) -> clicker.closeInventory());

		player.openInventory(gui.getInventory());
	}

	private void beginSearch(Player player) {
		waitingSearchInput.add(player.getUniqueId());
		player.closeInventory();
		player.sendMessage(miniMessage.deserialize("<aqua>🔍 Nhập từ khóa cần tìm trên chat. Gõ <white>huy</white> để hủy.</aqua>"));
	}

	private ItemStack categoryItem(Player player, HelpCategory category) {
		int commandCount = services.helpRegistry().countVisibleEntries(player, category.id());
		List<Component> lore = new ArrayList<>();
		lore.add(miniMessage.deserialize("<gray>Plugin: <white>" + category.plugin()));
		if (!category.description().isBlank()) {
			lore.add(miniMessage.deserialize("<gray>" + category.description()));
		}
		lore.add(Component.empty());
		lore.add(miniMessage.deserialize("<gray>● Tổng lệnh: <green>" + commandCount));
		lore.add(miniMessage.deserialize("<gray>Nhấn để xem danh sách lệnh chi tiết."));
		return item(category.material(), "<gold>" + category.title(), lore);
	}

	private ItemStack commandItem(HelpEntry entry) {
		List<Component> lore = new ArrayList<>();
		lore.add(miniMessage.deserialize("<gray>Plugin: <white>" + entry.plugin()));
		lore.add(miniMessage.deserialize("<gray>Danh mục: <white>" + entry.categoryId()));
		lore.add(Component.empty());
		lore.add(miniMessage.deserialize("<gray>Mô tả:</gray> <white>" + entry.description()));
		lore.add(Component.empty());
		lore.add(miniMessage.deserialize("<aqua>Cú pháp:</aqua>"));
		lore.add(miniMessage.deserialize(formatSyntax(entry)));

		if (!entry.arguments().isEmpty()) {
			lore.add(Component.empty());
			lore.add(miniMessage.deserialize("<gold>Tham số:</gold>"));
			for (HelpArgument argument : entry.arguments()) {
				String required = argument.required() ? "<red>Bắt buộc</red>" : "<yellow>Tùy chọn</yellow>";
				lore.add(miniMessage.deserialize("<gray>● <white>" + argument.syntaxToken() + "</white> - " + required));
				if (!argument.description().isBlank()) {
					lore.add(miniMessage.deserialize("<dark_gray>  ↳ " + argument.description()));
				}
				if (!argument.enumValues().isEmpty()) {
					lore.add(miniMessage.deserialize("<dark_gray>  ↳ Giá trị: <aqua>" + String.join("</aqua><gray> | </gray><aqua>", argument.enumValues()) + "</aqua>"));
				}
			}
		}

		if (!entry.usageExamples().isEmpty()) {
			lore.add(Component.empty());
			lore.add(miniMessage.deserialize("<green>Ví dụ:</green>"));
			for (String example : entry.usageExamples()) {
				lore.add(miniMessage.deserialize("<gray>• <white>" + example));
			}
		}

		return item(entry.material(), "<yellow>" + entry.command(), lore);
	}

	private String formatSyntax(HelpEntry entry) {
		StringBuilder builder = new StringBuilder("<white>").append(entry.command());
		for (HelpArgument argument : entry.arguments()) {
			if (argument.required()) {
				builder.append(" <red>").append(argument.syntaxToken()).append("</red>");
			} else {
				builder.append(" <yellow>").append(argument.syntaxToken()).append("</yellow>");
			}
		}
		return builder.toString();
	}

	private ItemStack navItem(Material material, String title, List<String> loreLines) {
		List<Component> lore = new ArrayList<>();
		for (String line : loreLines) {
			lore.add(miniMessage.deserialize(line));
		}
		return item(material, title, lore);
	}

	private ItemStack item(Material material, String title, List<Component> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(miniMessage.deserialize(title));
		meta.lore(lore);
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		item.setItemMeta(meta);
		return item;
	}

	private void fillFooter(GuiView gui) {
		ItemStack spacer = item(Material.GRAY_STAINED_GLASS_PANE, "<gray> ", List.of());
		for (int slot = 45; slot <= 53; slot++) {
			gui.setItem(slot, spacer);
		}
	}

	private int normalizeSize(int size) {
		int normalized = Math.max(9, size);
		int mod = normalized % 9;
		if (mod != 0) {
			normalized += 9 - mod;
		}
		return Math.min(normalized, 54);
	}

	private int maxPage(int itemCount) {
		if (itemCount <= 0) {
			return 0;
		}
		return (itemCount - 1) / CONTENT_SIZE;
	}

	private int clampPage(int page, int maxPage) {
		if (page < 0) {
			return 0;
		}
		return Math.min(page, maxPage);
	}

	private boolean matchesQuery(HelpEntry entry, String query) {
		String lower = query.toLowerCase(Locale.ROOT);
		if (entry.command().toLowerCase(Locale.ROOT).contains(lower)) {
			return true;
		}
		if (entry.description().toLowerCase(Locale.ROOT).contains(lower)) {
			return true;
		}
		for (String example : entry.usageExamples()) {
			if (example.toLowerCase(Locale.ROOT).contains(lower)) {
				return true;
			}
		}
		return false;
	}

	private void registerDefaultEntries() {
		services.helpRegistry().registerCategory(new HelpCategory(
			"lunacore-general",
			"LunaCore",
			"Luna Core",
			Material.NETHER_STAR,
			"Các lệnh hệ thống và trợ giúp của Luna Core"
		));

		services.helpRegistry().register(new HelpEntry(
			"LunaCore",
			"lunacore-general",
			Material.BOOK,
			"/help",
			"Mở giao diện trợ giúp theo danh mục plugin hoặc lệnh cụ thể.",
			null,
			List.of(
				"/help",
				"/help LunaCore",
				"/help /help"
			),
			List.of(
				new HelpArgument("plugin|command", false, "Mở nhanh theo plugin hoặc lệnh cụ thể.", List.of("LunaCore", "/help"))
			)
		));
	}
}
