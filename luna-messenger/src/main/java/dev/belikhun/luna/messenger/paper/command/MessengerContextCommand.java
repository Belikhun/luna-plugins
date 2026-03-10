package dev.belikhun.luna.messenger.paper.command;

import dev.belikhun.luna.messenger.paper.service.PaperMessengerGateway;
import dev.belikhun.luna.core.api.string.CommandStrings;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class MessengerContextCommand implements CommandExecutor, TabCompleter {
	private final PaperMessengerGateway gateway;

	public MessengerContextCommand(PaperMessengerGateway gateway) {
		this.gateway = gateway;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("Lệnh này chỉ dùng cho người chơi.");
			return true;
		}

		switch (command.getName().toLowerCase()) {
			case "nw" -> gateway.switchNetwork(player);
			case "sv" -> gateway.switchServer(player);
			case "msg" -> {
				if (args.length < 1) {
					player.sendRichMessage(CommandStrings.usage("/msg", CommandStrings.required("người_chơi", "text")));
					return true;
				}
				gateway.switchDirect(player, args[0]);
			}
			case "r" -> {
				if (args.length < 1) {
					player.sendRichMessage(CommandStrings.usage("/r", CommandStrings.required("nội_dung", "text")));
					return true;
				}
				gateway.sendReply(player, String.join(" ", args));
			}
			default -> {
				return false;
			}
		}

		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (command.getName().equalsIgnoreCase("msg") && args.length == 1) {
			return sender.getServer().getOnlinePlayers().stream()
				.map(Player::getName)
				.filter(name -> name.regionMatches(true, 0, args[0], 0, args[0].length()))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(20)
				.toList();
		}

		return List.of();
	}
}
