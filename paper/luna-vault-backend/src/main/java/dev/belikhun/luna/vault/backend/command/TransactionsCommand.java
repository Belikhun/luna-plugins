package dev.belikhun.luna.vault.backend.command;

import dev.belikhun.luna.vault.backend.gui.TransactionHistoryGuiController;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public final class TransactionsCommand implements BasicCommand {
	private final TransactionHistoryGuiController controller;

	public TransactionsCommand(TransactionHistoryGuiController controller) {
		this.controller = controller;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Chỉ người chơi mới dùng lệnh này.</red>");
			return;
		}

		int page = 0;
		if (args.length > 0) {
			try {
				page = Math.max(0, Integer.parseInt(args[0]) - 1);
			} catch (NumberFormatException exception) {
				player.sendRichMessage("<red>❌ Trang không hợp lệ.</red>");
				return;
			}
		}

		controller.open(player, page);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		return List.of();
	}

	@Override
	public String permission() {
		return "lunavault.transactions";
	}
}
