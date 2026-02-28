package dev.belikhun.luna.shop.command;

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

		if (args.length < 5) {
			sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin add <id> <category> <buyPrice> <sellPrice></yellow>");
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
			buyPrice = Double.parseDouble(args[3]);
			sellPrice = Double.parseDouble(args[4]);
		} catch (NumberFormatException exception) {
			sender.sendRichMessage("<red>❌ Giá mua/bán phải là số hợp lệ.</red>");
			return;
		}

		if (buyPrice < 0 || sellPrice < 0) {
			sender.sendRichMessage("<red>❌ Giá không được âm.</red>");
			return;
		}

		String id = args[1];
		String category = args[2];
		if (store.findCategory(category).isEmpty()) {
			sender.sendRichMessage("<red>❌ Danh mục chưa tồn tại. Dùng /shopadmin category create <id> trước.</red>");
			return;
		}

		ShopItem shopItem = ShopItem.fromItemStack(id, category, buyPrice, sellPrice, hand);
		store.upsert(shopItem);
		sender.sendRichMessage("<green>✔ Đã lưu item <white>" + shopItem.id() + "</white> vào danh mục <white>" + shopItem.category() + "</white>.</green>");
	}

	private void handleCategory(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin category <create|seticon|list|rename|delete> ...</yellow>");
			return;
		}

		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "list" -> {
				List<ShopCategory> categories = store.allCategories();
				sender.sendRichMessage("<gold>📁 Danh mục hiện có: <white>" + categories.size() + "</white></gold>");
				for (ShopCategory category : categories) {
					sender.sendRichMessage("<gray>● <white>" + category.id() + "</white> <dark_gray>(" + store.byCategory(category.id()).size() + " item)</dark_gray>");
				}
			}
			case "create" -> {
				if (!(sender instanceof Player player)) {
					sender.sendRichMessage("<red>❌ Chỉ người chơi mới có thể tạo danh mục từ item cầm tay.</red>");
					return;
				}

				if (args.length < 3) {
					sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin category create <id></yellow>");
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
					sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin category seticon <id></yellow>");
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
			case "rename" -> {
				if (args.length < 4) {
					sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin category rename <oldId> <newId></yellow>");
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
					sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin category delete <id> [moveTo]</yellow>");
					return;
				}

				String moveTo = args.length >= 4 ? args[3] : null;
				if (!store.deleteCategory(args[2], moveTo)) {
					sender.sendRichMessage("<red>❌ Không thể xóa danh mục. Nếu còn item, hãy chỉ định danh mục đích để chuyển: /shopadmin category delete <id> <moveTo></red>");
					return;
				}

				sender.sendRichMessage("<green>✔ Đã xóa danh mục <white>" + ShopItem.normalizeCategory(args[2]) + "</white>.</green>");
			}
			default -> sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin category <create|seticon|list|rename|delete> ...</yellow>");
		}
	}

	private void handleRemove(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendRichMessage("<yellow>ℹ Dùng: /shopadmin remove <id></yellow>");
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
		sender.sendRichMessage("<gold>📦 Danh sách mặt hàng hiện có: <white>" + items.size() + "</white></gold>");
		for (ShopItem item : items) {
			sender.sendRichMessage("<gray>● <white>" + item.id() + "</white> <dark_gray>(" + item.category() + ")</dark_gray> <green>Mua: " + item.buyPrice() + "</green> <yellow>Bán: " + item.sellPrice() + "</yellow>");
		}
	}

	private void handleReload(CommandSender sender) {
		store.load();
		sender.sendRichMessage("<green>✔ Đã reload dữ liệu shop từ items.yml.</green>");
	}

	private void sendHelp(CommandSender sender) {
		sender.sendRichMessage("<gradient:#f8c630:#f39c12>🛒 LunaShop Admin Commands</gradient>");
		sender.sendRichMessage("<gray>/shopadmin add <id> <category> <buyPrice> <sellPrice>");
		sender.sendRichMessage("<gray>/shopadmin category create <id>");
		sender.sendRichMessage("<gray>/shopadmin category seticon <id>");
		sender.sendRichMessage("<gray>/shopadmin category list");
		sender.sendRichMessage("<gray>/shopadmin category rename <oldId> <newId>");
		sender.sendRichMessage("<gray>/shopadmin category delete <id> [moveTo]");
		sender.sendRichMessage("<gray>/shopadmin remove <id>");
		sender.sendRichMessage("<gray>/shopadmin list");
		sender.sendRichMessage("<gray>/shopadmin reload");
		sender.sendRichMessage("<gray>/shopadmin open");
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			return List.of();
		}

		if (args.length == 1) {
			return filter(List.of("add", "category", "remove", "list", "reload", "open"), args[0]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("category")) {
			return filter(List.of("create", "seticon", "list", "rename", "delete"), args[1]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("category") && (args[1].equalsIgnoreCase("seticon") || args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("delete"))) {
			return filter(new ArrayList<>(store.categories()), args[2]);
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("rename")) {
			return filter(new ArrayList<>(store.categories()), args[3]);
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("category") && args[1].equalsIgnoreCase("delete")) {
			return filter(new ArrayList<>(store.categories()), args[3]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
			List<String> ids = new ArrayList<>();
			for (ShopItem item : store.all()) {
				ids.add(item.id());
			}
			return filter(ids, args[1]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
			return filter(new ArrayList<>(store.categories()), args[2]);
		}

		return List.of();
	}

	@Override
	public String permission() {
		return PERMISSION;
	}

	private List<String> filter(List<String> values, String input) {
		String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
		return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
	}
}