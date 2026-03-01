package dev.belikhun.luna.core.api.help;

import dev.belikhun.luna.core.LunaCoreServices;
import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.gui.LunaPagination;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.ui.LunaLore;
import dev.belikhun.luna.core.api.ui.LunaUi;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

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
		GuiView gui = new GuiView(size, LunaUi.guiTitle("📚 Trung Tâm Trợ Giúp Luna"));
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

		int maxPage = LunaPagination.maxPage(categories.size(), CONTENT_SIZE);
		int currentPage = LunaPagination.clampPage(page, maxPage);
		int start = currentPage * CONTENT_SIZE;
		int end = Math.min(categories.size(), start + CONTENT_SIZE);
		for (int index = start; index < end; index++) {
			HelpCategory category = categories.get(index);
			int slot = index - start;
			gui.setItem(slot, categoryItem(player, category), (clicker, event, view) -> openCategoryPage(clicker, category, 0));
		}

		fillFooter(gui);
		if (currentPage > 0) {
			gui.setItem(45, navItem(Material.ARROW, "<yellow>← Trang trước", List.of("<gray>Quay về trang danh mục trước.")), (clicker, event, view) -> openFrontPage(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			gui.setItem(53, navItem(Material.ARROW, "<yellow>Trang sau →", List.of("<gray>Xem thêm danh mục lệnh.")), (clicker, event, view) -> openFrontPage(clicker, currentPage + 1));
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
		GuiView gui = new GuiView(size, LunaUi.guiTitle("♦ " + category.title()));
		guiManager.track(gui);

		List<HelpEntry> entries = services.helpRegistry().visibleEntriesByCategory(player, category.id());
		entries.sort(Comparator.comparing(entry -> entry.command().toLowerCase(Locale.ROOT)));
		int maxPage = LunaPagination.maxPage(entries.size(), CONTENT_SIZE);
		int currentPage = LunaPagination.clampPage(page, maxPage);
		int start = currentPage * CONTENT_SIZE;
		int end = Math.min(entries.size(), start + CONTENT_SIZE);
		for (int index = start; index < end; index++) {
			HelpEntry entry = entries.get(index);
			int slot = index - start;
			gui.setItem(slot, commandItem(entry));
		}

		fillFooter(gui);
		if (currentPage > 0) {
			gui.setItem(45, navItem(Material.ARROW, "<yellow>← Trang trước", List.of("<gray>Quay về trang lệnh trước.")), (clicker, event, view) -> openCategoryPage(clicker, category, currentPage - 1));
		}
		if (currentPage < maxPage) {
			gui.setItem(53, navItem(Material.ARROW, "<yellow>Trang sau →", List.of("<gray>Xem thêm lệnh trong danh mục.")), (clicker, event, view) -> openCategoryPage(clicker, category, currentPage + 1));
		}

		gui.setItem(49, navItem(Material.CHEST, "<aqua>Quay lại danh mục", List.of("<gray>Trở về trang chính trợ giúp.")), (clicker, event, view) -> openFrontPage(clicker, 0));
		gui.setItem(52, navItem(Material.OAK_DOOR, "<red>Đóng", List.of("<gray>Đóng giao diện trợ giúp.")), (clicker, event, view) -> clicker.closeInventory());

		player.openInventory(gui.getInventory());
	}

	private void openSearchResults(Player player, String query, int page) {
		int size = normalizeSize(services.configStore().get("help.gui.size").asInt(54));
		GuiView gui = new GuiView(size, LunaUi.guiTitle("🔍 Kết Quả: " + query));
		guiManager.track(gui);

		List<HelpEntry> entries = services.helpRegistry().search(player, query);
		entries.sort(Comparator.comparing(entry -> entry.command().toLowerCase(Locale.ROOT)));
		int maxPage = LunaPagination.maxPage(entries.size(), CONTENT_SIZE);
		int currentPage = LunaPagination.clampPage(page, maxPage);
		int start = currentPage * CONTENT_SIZE;
		int end = Math.min(entries.size(), start + CONTENT_SIZE);
		for (int index = start; index < end; index++) {
			HelpEntry entry = entries.get(index);
			int slot = index - start;
			gui.setItem(slot, commandItem(entry));
		}

		fillFooter(gui);
		if (currentPage > 0) {
			gui.setItem(45, navItem(Material.ARROW, "<yellow>← Trang trước", List.of("<gray>Quay về trang kết quả trước.")), (clicker, event, view) -> openSearchResults(clicker, query, currentPage - 1));
		}
		if (currentPage < maxPage) {
			gui.setItem(53, navItem(Material.ARROW, "<yellow>Trang sau →", List.of("<gray>Xem thêm kết quả.")), (clicker, event, view) -> openSearchResults(clicker, query, currentPage + 1));
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
		addLoreLines(lore, "<white>Plugin: <yellow>" + category.plugin());
		if (!category.description().isBlank()) {
			addLoreLines(lore, "<white>" + category.description());
		}
		lore.add(Component.empty());
		addLoreLines(lore, "<white>● Tổng lệnh: <green>" + commandCount);
		addLoreLines(lore, "<white>Nhấn để xem lệnh chi tiết");
		return item(category.material(), "<gold>" + category.title(), lore);
	}

	private ItemStack commandItem(HelpEntry entry) {
		List<Component> lore = new ArrayList<>();
		addLoreLines(lore, "<white>Plugin: <yellow>" + entry.plugin());
		addLoreLines(lore, "<white>Danh mục: <yellow>" + entry.categoryId());
		lore.add(Component.empty());
		addLoreLines(lore, "<white>Mô tả: <yellow>" + entry.description());
		lore.add(Component.empty());
		addLoreLines(lore, "<aqua>Cú pháp:");
		addLoreLines(lore, formatSyntax(entry));

		if (!entry.arguments().isEmpty()) {
			lore.add(Component.empty());
			addLoreLines(lore, "<gold>Tham số:");
			for (HelpArgument argument : entry.arguments()) {
				String required = argument.required() ? "<red>Bắt buộc</red>" : "<yellow>Tùy chọn</yellow>";
				addLoreLines(lore, "<white>● <yellow>" + argument.syntaxToken() + "</yellow> - " + required);
				if (!argument.description().isBlank()) {
					addLoreLines(lore, "<white>↳ " + argument.description());
				}
				if (!argument.enumValues().isEmpty()) {
					addLoreLines(lore, "<white>↳ Giá trị: <aqua>" + String.join("</aqua><white> | </white><aqua>", argument.enumValues()) + "</aqua>");
				}
			}
		}

		if (!entry.usageExamples().isEmpty()) {
			lore.add(Component.empty());
			addLoreLines(lore, "<green>Ví dụ:");
			for (String example : entry.usageExamples()) {
				addLoreLines(lore, "<white>• </white>" + CommandStrings.syntaxRaw(example));
			}
		}

		return item(entry.material(), CommandStrings.syntaxRaw(entry.command()), lore);
	}

	private String formatSyntax(HelpEntry entry) {
		List<CommandStrings.Segment> segments = new ArrayList<>();
		for (HelpArgument argument : entry.arguments()) {
			String token = normalizeArgumentName(argument.syntaxToken());
			String type = normalizeArgumentType(argument);
			if (argument.required()) {
				segments.add(CommandStrings.required(token, type));
				continue;
			}
			segments.add(CommandStrings.optional(token, type));
		}
		return CommandStrings.syntaxRaw(entry.command(), segments.toArray(new CommandStrings.Segment[0]));
	}

	private String normalizeArgumentName(String syntaxToken) {
		if (syntaxToken == null) {
			return "tham_số";
		}

		String normalized = syntaxToken.trim();
		if ((normalized.startsWith("<") && normalized.endsWith(">")) || (normalized.startsWith("[") && normalized.endsWith("]"))) {
			normalized = normalized.substring(1, normalized.length() - 1).trim();
		}

		int typeSplit = normalized.indexOf(':');
		if (typeSplit >= 0 && typeSplit < normalized.length() - 1) {
			normalized = normalized.substring(0, typeSplit).trim();
		}

		return normalized.isBlank() ? "tham_số" : normalized;
	}

	private String normalizeArgumentType(HelpArgument argument) {
		String syntaxToken = argument.syntaxToken() == null ? "" : argument.syntaxToken().trim();
		int typeSplit = syntaxToken.indexOf(':');
		if (typeSplit >= 0 && typeSplit < syntaxToken.length() - 1) {
			String explicitType = syntaxToken.substring(typeSplit + 1).trim();
			if (!explicitType.isBlank()) {
				return explicitType;
			}
		}

		if (!argument.enumValues().isEmpty()) {
			return String.join("|", argument.enumValues());
		}

		String tokenLower = normalizeArgumentName(syntaxToken).toLowerCase(Locale.ROOT);
		if (tokenLower.contains("price") || tokenLower.contains("amount") || tokenLower.contains("count") || tokenLower.contains("page") || tokenLower.contains("số")) {
			return "number";
		}

		return "text";
	}

	private ItemStack navItem(Material material, String title, List<String> loreLines) {
		List<Component> lore = new ArrayList<>();
		for (String line : loreLines) {
			for (String wrapped : LunaLore.wrapLoreLine(line)) {
				lore.add(wrapped.isEmpty() ? Component.empty() : LunaUi.mini(wrapped));
			}
		}
		return item(material, title, lore);
	}

	private void addLoreLines(List<Component> lore, String line) {
		for (String wrapped : LunaLore.wrapLoreLine(line)) {
			lore.add(wrapped.isEmpty() ? Component.empty() : miniMessage.deserialize(wrapped));
		}
	}

	private ItemStack item(Material material, String title, List<Component> lore) {
		return LunaUi.item(material, title, lore);
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
