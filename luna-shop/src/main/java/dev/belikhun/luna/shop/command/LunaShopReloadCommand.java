package dev.belikhun.luna.shop.command;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.shop.LunaShopPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

public final class LunaShopReloadCommand implements BasicCommand {
	private static final String PERMISSION = "lunashop.admin";

	private final LunaShopPlugin plugin;

	public LunaShopReloadCommand(LunaShopPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			sender.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
			plugin.reloadShopModules();
			sender.sendRichMessage("<green>✔ Đã reload LunaShop config, dữ liệu và module liên quan.</green>");
			return;
		}

		sender.sendRichMessage(CommandStrings.usage("/lunashop", CommandStrings.literal("reload")));
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			return List.of();
		}

		if (args.length == 0) {
			return List.of("reload");
		}

		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("reload"), args[0]);
		}

		return List.of();
	}

	@Override
	public String permission() {
		return PERMISSION;
	}
}
