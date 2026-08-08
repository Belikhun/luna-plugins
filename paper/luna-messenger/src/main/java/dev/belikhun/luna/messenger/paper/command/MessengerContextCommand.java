package dev.belikhun.luna.messenger.paper.command;

import dev.belikhun.luna.messenger.paper.service.PaperMessengerGateway;
import dev.belikhun.luna.core.api.string.CommandStrings;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public final class MessengerContextCommand implements BasicCommand {
	public enum ContextType {
		NETWORK,
		SERVER,
		DIRECT,
		REPLY
	}

	private final PaperMessengerGateway gateway;
	private final ContextType contextType;

	public MessengerContextCommand(PaperMessengerGateway gateway, ContextType contextType) {
		this.gateway = gateway;
		this.contextType = contextType;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Lệnh này chỉ dùng cho người chơi.</red>");
			return;
		}

		switch (contextType) {
			case NETWORK -> gateway.switchNetwork(player);
			case SERVER -> gateway.switchServer(player);
			case DIRECT -> {
				if (args.length < 1) {
					player.sendRichMessage(CommandStrings.usage("/msg", CommandStrings.required("người_chơi", "text")));
					return;
				}
				if (args.length == 1) {
					gateway.switchDirect(player, args[0]);
					return;
				}

				gateway.sendDirect(player, args[0], String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
			}
			case REPLY -> {
				if (args.length < 1) {
					player.sendRichMessage(CommandStrings.usage("/r", CommandStrings.required("nội_dung", "text")));
					return;
				}
				gateway.sendReply(player, String.join(" ", args));
			}
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (contextType == ContextType.DIRECT && args.length == 1) {
			String senderName = sender instanceof Player player ? player.getName() : "";
			return gateway.suggestDirectTargets(args[0], senderName);
		}

		return List.of();
	}
}
