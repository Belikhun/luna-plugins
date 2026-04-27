package dev.belikhun.luna.auth.backend.command;

import dev.belikhun.luna.core.api.auth.AuthChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.ui.LunaPalette;
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
			sender.sendRichMessage(error("❌ Lệnh này chỉ dùng trong game."));
			return;
		}

		if ("login".equals(mode)) {
			if (args.length < 1) {
				player.sendRichMessage(CommandStrings.usage("/login", CommandStrings.required("mat_khau", "text")));
				return;
			}
			pluginMessaging.send(player, AuthChannels.COMMAND_REQUEST, writer -> {
				writer.writeUtf("login");
				writer.writeUuid(player.getUniqueId());
				writer.writeUtf(player.getName());
				writer.writeUtf(args[0]);
			});
			return;
		}

		if (args.length < 2) {
			player.sendRichMessage(CommandStrings.usage("/register", CommandStrings.required("mat_khau", "text"), CommandStrings.required("nhap_lai", "text")));
			return;
		}
		pluginMessaging.send(player, AuthChannels.COMMAND_REQUEST, writer -> {
			writer.writeUtf("register");
			writer.writeUuid(player.getUniqueId());
			writer.writeUtf(player.getName());
			writer.writeUtf(args[0]);
			writer.writeUtf(args[1]);
		});
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		return List.of();
	}

	private String error(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}
}
