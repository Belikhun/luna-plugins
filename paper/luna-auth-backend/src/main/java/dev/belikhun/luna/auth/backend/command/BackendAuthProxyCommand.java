package dev.belikhun.luna.auth.backend.command;

import dev.belikhun.luna.core.api.auth.AuthChannels;
import dev.belikhun.luna.core.api.auth.AuthMessages;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public final class BackendAuthProxyCommand implements BasicCommand {
	private final String mode;
	private final PluginMessageBus<Player, Player> pluginMessaging;

	public BackendAuthProxyCommand(String mode, PluginMessageBus<Player, Player> pluginMessaging) {
		this.mode = mode;
		this.pluginMessaging = pluginMessaging;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage(AuthMessages.notAPlayer());
			return;
		}

		if ("login".equals(mode)) {
			if (args.length < 1) {
				player.sendRichMessage(AuthMessages.loginUsage());
				return;
			}

			boolean sent = pluginMessaging.send(player, AuthChannels.COMMAND_REQUEST, writer -> {
				writer.writeUtf("login");
				writer.writeUuid(player.getUniqueId());
				writer.writeUtf(player.getName());
				writer.writeUtf(args[0]);
			});

			if (!sent) {
				player.sendRichMessage(AuthMessages.commandSendFailed());
			}

			return;
		}

		if (args.length < 2) {
			player.sendRichMessage(AuthMessages.registerUsage());
			return;
		}

		boolean sent = pluginMessaging.send(player, AuthChannels.COMMAND_REQUEST, writer -> {
			writer.writeUtf("register");
			writer.writeUuid(player.getUniqueId());
			writer.writeUtf(player.getName());
			writer.writeUtf(args[0]);
			writer.writeUtf(args[1]);
		});

		if (!sent) {
			player.sendRichMessage(AuthMessages.commandSendFailed());
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		return List.of();
	}
}
