package dev.belikhun.luna.shop.command;

import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.shop.gui.ShopGuiController;
import dev.belikhun.luna.shop.model.ShopCategory;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.store.ShopItemStore;
import org.bukkit.Material;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class ShopAdminCommand implements BasicCommand {
	private static final String PERMISSION = "lunashop.admin";
	private static final String ADMIN_HEADER = "<gradient:" + LunaPalette.GOLD_300 + ":" + LunaPalette.AMBER_500 + ">★ LunaShop Admin Commands</gradient>";
	private static final String CATEGORY_EXAMPLE_VIP = "<gradient:" + LunaPalette.SUCCESS_500 + ":" + LunaPalette.PRIMARY_500 + ">Trang Bị VIP</gradient>";

	private final JavaPlugin plugin;
	private final ShopItemStore store;
	private final ShopGuiController guiController;

	public ShopAdminCommand(JavaPlugin plugin, ShopItemStore store, ShopGuiController guiController) {
		this.plugin = plugin;
		this.store = store;
		this.guiController = guiController;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			sender.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (args.length == 0) {
			sendHelp(sender);
			return;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "add" -> handleAdd(sender, args);
			case "category" -> handleCategory(sender, args);
			case "remove" -> handleRemove(sender, args);
			case "list" -> handleList(sender);
			case "reload" -> handleReload(sender);
			case "open" -> {
				if (sender instanceof Player player) {
					guiController.openManagementMenu(player, 0);
				} else {
					sender.sendRichMessage("<red>❌ Chỉ người chơi mới mở GUI được.</red>");
				}
			}
			default -> sendHelp(sender);
		}
	}

	private void handleAdd(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Chỉ người chơi mới thêm item từ tay được.</red>");
			return;
		}

		if (args.length < 4) {
			sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("add"), CommandStrings.required("category", "text"), CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number")));
			return;
		}

		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType() == Material.AIR) {
			sender.sendRichMessage("<red>❌ Bạn cần cầm vật phẩm trên tay để thêm vào shop.</red>");
			return;
		}

		double buyPrice;
		double sellPrice;
		try {
			buyPrice = Double.parseDouble(args[2]);
			sellPrice = Double.parseDouble(args[3]);
		} catch (NumberFormatException exception) {
			sender.sendRichMessage("<red>❌ Giá mua/bán phải là số hợp lệ.</red>");
			return;
		}

		if (buyPrice < 0 || sellPrice < 0) {
			sender.sendRichMessage("<red>❌ Giá không được âm.</red>");
			return;
		}

		String category = args[1];
		if (store.findCategory(category).isEmpty()) {
			sender.sendRichMessage("<red>❌ Danh mục chưa tồn tại. Dùng " + CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("create"), CommandStrings.required("id", "text")) + " <red>trước.</red>");
			return;
		}

		var duplicate = store.findBySimilarItem(hand);
		if (duplicate.isPresent()) {
			sender.sendRichMessage("<red>❌ Item này đã có trong shop với id <white>" + duplicate.get().id() + "</white>.</red>");
			return;
		}

		ShopItem shopItem = ShopItem.fromItemStackAutoId(category, buyPrice, sellPrice, hand);
		store.upsert(shopItem);
		sender.sendRichMessage("<green>✔ Đã lưu item <white>" + shopItem.id() + "</white> vào danh mục <white>" + shopItem.category() + "</white>.</green>");
	}

	private void handleCategory(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.requiredChoice("hành_động", "create", "seticon", "displayname", "list", "rename", "delete"), CommandStrings.optional("tham_số", "...")));
			return;
		}

		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "list" -> {
				List<ShopCategory> categories = store.allCategories();
					sender.sendRichMessage("<gold>♦ Danh mục hiện có: <white>" + categories.size() + "</white></gold>");
				for (ShopCategory category : categories) {
					String display = category.hasDisplayName() ? category.displayName() : "<gray>(mặc định)</gray>";
					sender.sendRichMessage("<gray>● <white>" + category.id() + "</white> - " + display + " <dark_gray>(" + store.byCategory(category.id()).size() + " item)</dark_gray>");
				}
			}
			case "create" -> {
				if (!(sender instanceof Player player)) {
					sender.sendRichMessage("<red>❌ Chỉ người chơi mới có thể tạo danh mục từ item cầm tay.</red>");
					return;
				}

				if (args.length < 3) {
					sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("create"), CommandStrings.required("id", "text")));
					return;
				}

				if (store.findCategory(args[2]).isPresent()) {
					sender.sendRichMessage("<red>❌ Danh mục đã tồn tại.</red>");
					return;
				}

				ItemStack hand = player.getInventory().getItemInMainHand();
				if (hand.getType() == Material.AIR) {
					sender.sendRichMessage("<red>❌ Bạn cần cầm item đại diện category trên tay.</red>");
					return;
				}

				store.upsertCategory(ShopCategory.fromIcon(args[2], hand));
				sender.sendRichMessage("<green>✔ Đã tạo danh mục <white>" + ShopItem.normalizeCategory(args[2]) + "</white>.</green>");
			}
			case "seticon" -> {
				if (!(sender instanceof Player player)) {
					sender.sendRichMessage("<red>❌ Chỉ người chơi mới có thể đặt icon từ item cầm tay.</red>");
					return;
				}

				if (args.length < 3) {
					sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("seticon"), CommandStrings.required("id", "text")));
					return;
				}

				if (store.findCategory(args[2]).isEmpty()) {
					sender.sendRichMessage("<red>❌ Danh mục không tồn tại.</red>");
					return;
				}

				ItemStack hand = player.getInventory().getItemInMainHand();
				if (hand.getType() == Material.AIR) {
					sender.sendRichMessage("<red>❌ Bạn cần cầm item đại diện category trên tay.</red>");
					return;
				}

				store.upsertCategoryIcon(args[2], hand);
				sender.sendRichMessage("<green>✔ Đã cập nhật icon cho danh mục <white>" + ShopItem.normalizeCategory(args[2]) + "</white>.</green>");
			}
			case "displayname" -> {
				if (args.length < 4) {
					sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("displayname"), CommandStrings.required("id", "text"), CommandStrings.required("displayName", "mini_message...")));
					return;
				}

				if (store.findCategory(args[2]).isEmpty()) {
					sender.sendRichMessage("<red>❌ Danh mục không tồn tại.</red>");
					return;
				}

				String displayName = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
				if (displayName.isBlank()) {
					sender.sendRichMessage("<red>❌ Tên hiển thị không được để trống.</red>");
					return;
				}

				store.updateCategoryDisplayName(args[2], displayName);
				sender.sendRichMessage("<green>✔ Đã cập nhật tên hiển thị cho danh mục <white>" + ShopItem.normalizeCategory(args[2]) + "</white>.</green>");
			}
			case "rename" -> {
				if (args.length < 4) {
					sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("rename"), CommandStrings.required("oldId", "text"), CommandStrings.required("newId", "text")));
					return;
				}

				if (store.findCategory(args[2]).isEmpty()) {
					sender.sendRichMessage("<red>❌ Danh mục cũ không tồn tại.</red>");
					return;
				}

				if (store.findCategory(args[3]).isPresent()) {
					sender.sendRichMessage("<red>❌ Danh mục mới đã tồn tại.</red>");
					return;
				}

				store.renameCategory(args[2], args[3]);
				sender.sendRichMessage("<green>✔ Đã đổi tên danh mục từ <white>" + ShopItem.normalizeCategory(args[2]) + "</white> sang <white>" + ShopItem.normalizeCategory(args[3]) + "</white>.</green>");
			}
			case "delete" -> {
				if (args.length < 3) {
					sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("delete"), CommandStrings.required("id", "text"), CommandStrings.optional("moveTo", "text")));
					return;
				}

				String moveTo = args.length >= 4 ? args[3] : null;
				if (!store.deleteCategory(args[2], moveTo)) {
					sender.sendRichMessage("<red>❌ Không thể xóa danh mục. Nếu còn item, hãy chỉ định danh mục đích để chuyển: </red>" + CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("delete"), CommandStrings.required("id", "text"), CommandStrings.required("moveTo", "text")));
					return;
				}

				sender.sendRichMessage("<green>✔ Đã xóa danh mục <white>" + ShopItem.normalizeCategory(args[2]) + "</white>.</green>");
			}
			default -> sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("category"), CommandStrings.requiredChoice("hành_động", "create", "seticon", "displayname", "list", "rename", "delete"), CommandStrings.optional("tham_số", "...")));
		}
	}

	private void handleRemove(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendRichMessage(CommandStrings.usage("/shopadmin", CommandStrings.literal("remove"), CommandStrings.required("id", "text")));
			return;
		}

		if (store.remove(args[1])) {
			sender.sendRichMessage("<green>✔ Đã xóa item khỏi shop.</green>");
			return;
		}

		sender.sendRichMessage("<red>❌ Không tìm thấy item theo id.</red>");
	}

	private void handleList(CommandSender sender) {
		List<ShopItem> items = store.all();
		sender.sendRichMessage("<gold>♦ Danh sách mặt hàng hiện có: <white>" + items.size() + "</white></gold>");
		for (ShopItem item : items) {
			sender.sendRichMessage("<gray>● <white>" + item.id() + "</white> <dark_gray>(" + item.category() + ")</dark_gray> <green>Mua: " + item.buyPrice() + "</green> <yellow>Bán: " + item.sellPrice() + "</yellow>");
		}
	}

	private void handleReload(CommandSender sender) {
		store.load();
		sender.sendRichMessage("<green>✔ Đã reload dữ liệu shop từ items.yml.</green>");
	}

	private void sendHelp(CommandSender sender) {
		sender.sendRichMessage(ADMIN_HEADER);
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("add"), CommandStrings.required("category", "text"), CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("create"), CommandStrings.required("id", "text")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("seticon"), CommandStrings.required("id", "text")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("displayname"), CommandStrings.required("id", "text"), CommandStrings.required("displayName", "mini_message...")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("list")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("rename"), CommandStrings.required("oldId", "text"), CommandStrings.required("newId", "text")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("delete"), CommandStrings.required("id", "text"), CommandStrings.optional("moveTo", "text")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("remove"), CommandStrings.required("id", "text")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("list")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("reload")));
		sender.sendRichMessage(CommandStrings.syntax("/shopadmin", CommandStrings.literal("open")));
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			return List.of();
		}

		if (args.length == 0) {
			return List.of("add", "category", "remove", "list", "reload", "open");
		}

		if (args.length == 1 && args[0].isEmpty()) {
			return List.of("add", "category", "remove", "list", "reload", "open");
		}

		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("add", "category", "remove", "list", "reload", "open"), args[0]);
		}

		if (args.length == 2 && args[1].isEmpty() && args[0].equalsIgnoreCase("category")) {
			return List.of("create", "seticon", "displayname", "list", "rename", "delete");
		}

		if (args.length == 2 && args[1].isEmpty() && args[0].equalsIgnoreCase("remove")) {
			List<String> ids = new ArrayList<>();
			for (ShopItem item : store.all()) {
				ids.add(item.id());
			}
			return ids.stream().sorted().toList();
		}

		if (args.length == 2 && args[1].isEmpty() && args[0].equalsIgnoreCase("add")) {
			return new ArrayList<>(store.categories()).stream().sorted().toList();
		}

		if (args.length == 3 && args[2].isEmpty() && args[0].equalsIgnoreCase("add")) {
			return List.of("100", "1000", "5000");
		}

		if (args.length == 4 && args[3].isEmpty() && args[0].equalsIgnoreCase("add")) {
			return List.of("50", "500", "2500");
		}

		if (args.length == 3 && args[2].isEmpty() && args[0].equalsIgnoreCase("category") && (args[1].equalsIgnoreCase("seticon") || args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("delete"))) {
			return new ArrayList<>(store.categories()).stream().sorted().toList();
		}

		if (args.length == 3 && args[2].isEmpty() && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("displayname")) {
			return new ArrayList<>(store.categories()).stream().sorted().toList();
		}

		if (args.length == 4 && args[3].isEmpty() && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("displayname")) {
			return List.of("<gold>Khối <yellow>Xây Dựng</yellow></gold>", CATEGORY_EXAMPLE_VIP, "<red>Đá Quý</red>");
		}

		if (args.length >= 4 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("displayname")) {
			String input = args[args.length - 1];
			List<String> examples = List.of(
				"<gold>Khối <yellow>Xây Dựng</yellow></gold>",
				CATEGORY_EXAMPLE_VIP,
				"<red>Đá Quý</red>"
			);
			if (input == null || input.isEmpty()) {
				return examples;
			}

			return CommandCompletions.filterPrefix(new ArrayList<>(examples), input);
		}

		if (args.length == 4 && args[3].isEmpty() && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("rename")) {
			return new ArrayList<>(store.categories()).stream().sorted().toList();
		}

		if (args.length == 4 && args[3].isEmpty() && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("delete")) {
			return new ArrayList<>(store.categories()).stream().sorted().toList();
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("category")) {
			return CommandCompletions.filterPrefix(List.of("create", "seticon", "displayname", "list", "rename", "delete"), args[1]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
			return CommandCompletions.filterPrefix(new ArrayList<>(store.categories()), args[1]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
			return CommandCompletions.filterPrefix(List.of("100", "1000", "5000"), args[2]);
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("add")) {
			return CommandCompletions.filterPrefix(List.of("50", "500", "2500"), args[3]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("category") && (args[1].equalsIgnoreCase("seticon") || args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("delete"))) {
			return CommandCompletions.filterPrefix(new ArrayList<>(store.categories()), args[2]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("displayname")) {
			return CommandCompletions.filterPrefix(new ArrayList<>(store.categories()), args[2]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("create")) {
			return CommandCompletions.filterPrefix(List.of("new-category"), args[2]);
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("rename")) {
			return CommandCompletions.filterPrefix(new ArrayList<>(store.categories()), args[3]);
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("delete")) {
			return CommandCompletions.filterPrefix(new ArrayList<>(store.categories()), args[3]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
			List<String> ids = new ArrayList<>();
			for (ShopItem item : store.all()) {
				ids.add(item.id());
			}
			return CommandCompletions.filterPrefix(ids, args[1]);
		}

		return List.of();
	}

	@Override
	public String permission() {
		return PERMISSION;
	}

}