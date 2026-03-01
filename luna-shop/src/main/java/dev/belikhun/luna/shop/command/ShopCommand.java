package dev.belikhun.luna.shop.command;

import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.shop.gui.ShopGuiController;
import dev.belikhun.luna.shop.model.ShopItem;
import dev.belikhun.luna.shop.store.ShopItemStore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ShopCommand implements BasicCommand {
	private final ShopGuiController guiController;
	private final ShopItemStore store;

	public ShopCommand(ShopGuiController guiController, ShopItemStore store) {
		this.guiController = guiController;
		this.store = store;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Lệnh này chỉ có thể dùng trong game.</red>");
			return;
		}

		if (args.length == 0) {
			guiController.openMainMenu(player, 0);
			return;
		}

		if (args[0].equalsIgnoreCase("search")) {
			if (args.length < 2) {
				sender.sendRichMessage(CommandStrings.usage("/shop", CommandStrings.literal("search"), CommandStrings.required("từ_khóa", "text")));
				return;
			}

			String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
			guiController.openSearchMenu(player, query, 0);
			return;
		}

		if (args[0].equalsIgnoreCase("category")) {
			if (args.length < 2) {
				sender.sendRichMessage(CommandStrings.usage("/shop", CommandStrings.literal("category"), CommandStrings.required("tên_danh_mục", "text")));
				return;
			}

			guiController.openCategoryMenu(player, args[1], 0);
			return;
		}

		if (store.findCategory(args[0]).isPresent()) {
			guiController.openCategoryMenu(player, args[0], 0);
			return;
		}

		guiController.openMainMenu(player, 0);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (args.length == 0) {
			List<String> root = new ArrayList<>();
			root.add("search");
			root.add("category");
			root.addAll(store.categories());
			return root.stream().sorted().toList();
		}

		if (args.length == 1 && args[0].isEmpty()) {
			List<String> root = new ArrayList<>();
			root.add("search");
			root.add("category");
			root.addAll(store.categories());
			return root.stream().sorted().toList();
		}

		if (args.length == 1) {
			List<String> root = new ArrayList<>();
			root.add("search");
			root.add("category");
			root.addAll(store.categories());
			return CommandCompletions.filterPrefix(root, args[0]);
		}

		if (args.length == 2 && args[1].isEmpty() && args[0].equalsIgnoreCase("category")) {
			return new ArrayList<>(store.categories()).stream().sorted().toList();
		}

		if (args.length == 2 && args[1].isEmpty() && args[0].equalsIgnoreCase("search")) {
			List<String> values = new ArrayList<>();
			for (ShopItem item : store.all()) {
				values.add(item.id());
				values.add(item.category());
			}
			return values.stream().sorted().toList();
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("category")) {
			return CommandCompletions.filterPrefix(new ArrayList<>(store.categories()), args[1]);
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
			List<String> values = new ArrayList<>();
			for (ShopItem item : store.all()) {
				values.add(item.id());
				values.add(item.category());
			}
			return CommandCompletions.filterPrefix(values, args[args.length - 1]);
		}

		return List.of();
	}
}