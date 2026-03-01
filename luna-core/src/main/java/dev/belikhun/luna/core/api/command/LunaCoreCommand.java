package dev.belikhun.luna.core.api.command;

import dev.belikhun.luna.core.LunaCorePlugin;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

public final class LunaCoreCommand implements BasicCommand {
	private static final String PERMISSION = "lunacore.admin";

	private final LunaCorePlugin plugin;

	public LunaCoreCommand(LunaCorePlugin plugin) {
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
			plugin.reloadCoreModules();
			sender.sendRichMessage("<green>✔ Đã reload LunaCore config và module liên quan.</green>");
			return;
		}

		sender.sendRichMessage(CommandStrings.usage("/lunacore", CommandStrings.literal("reload")));
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
