package dev.belikhun.luna.messenger.paper.command;

import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.messenger.paper.service.PaperMessengerGateway;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public final class MessengerPokeCommand implements BasicCommand {
	private final PaperMessengerGateway gateway;

	public MessengerPokeCommand(PaperMessengerGateway gateway) {
		this.gateway = gateway;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Lệnh này chỉ dùng cho người chơi.</red>");
			return;
		}

		if (args.length < 1) {
			player.sendRichMessage(CommandStrings.usage("/poke", CommandStrings.required("người_chơi", "text")));
			return;
		}

		gateway.sendPoke(player, args[0]);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (args.length == 1) {
			String senderName = sender instanceof Player player ? player.getName() : "";
			return gateway.suggestDirectTargets(args[0], senderName);
		}

		return List.of();
	}
}
