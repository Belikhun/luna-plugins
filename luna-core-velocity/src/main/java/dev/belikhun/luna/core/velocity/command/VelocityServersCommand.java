package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;

import java.util.List;

public final class VelocityServersCommand implements SimpleCommand {
	private final VelocityPluginMessagingBus messagingBus;
	private final VelocityServerSelectorConfig config;

	public VelocityServersCommand(VelocityPluginMessagingBus messagingBus, VelocityServerSelectorConfig config) {
		this.messagingBus = messagingBus;
		this.config = config;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!(source instanceof Player player)) {
			source.sendRichMessage(config.playerOnlyMessage());
			return;
		}

		player.sendRichMessage(config.openingMessage());
		ServerConnection connection = player.getCurrentServer().orElse(null);
		if (connection == null) {
			player.sendRichMessage("<red>❌ Không thể mở menu khi chưa ở backend.</red>");
			return;
		}

		messagingBus.send(connection, CoreServerSelectorMessageChannels.OPEN_MENU, writer -> {
		});
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}
}
