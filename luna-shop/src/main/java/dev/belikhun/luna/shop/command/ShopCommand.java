package dev.belikhun.luna.shop.command;

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
import java.util.Locale;

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
				sender.sendRichMessage("<yellow>ℹ Dùng: /shop search <từ_khóa></yellow>");
				return;
			}

			String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
			guiController.openSearchMenu(player, query, 0);
			return;
		}

		if (args[0].equalsIgnoreCase("category")) {
			if (args.length < 2) {
				sender.sendRichMessage("<yellow>ℹ Dùng: /shop category <tên_danh_mục></yellow>");
				return;
			}

			guiController.openCategoryMenu(player, args[1], 0);
			return;
		}

		guiController.openMainMenu(player, 0);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (args.length == 1) {
			return filter(List.of("search", "category"), args[0]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("category")) {
			return filter(new ArrayList<>(store.categories()), args[1]);
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
			List<String> values = new ArrayList<>();
			for (ShopItem item : store.all()) {
				values.add(item.id());
			}
			return filter(values, args[args.length - 1]);
		}

		return List.of();
	}

	private List<String> filter(List<String> values, String input) {
		String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
		return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
	}
}