package dev.belikhun.luna.migrator.listener;

import dev.belikhun.luna.migrator.command.MigrationCommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class MigrationReconnectGuardListener implements Listener {
	private final MigrationCommand migrationCommand;

	public MigrationReconnectGuardListener(MigrationCommand migrationCommand) {
		this.migrationCommand = migrationCommand;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
		if (!migrationCommand.isMigrationInProgress(event.getUniqueId())) {
			return;
		}

		event.disallow(
			AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
			MiniMessage.miniMessage().deserialize(migrationCommand.reconnectBlockedMessage(event.getUniqueId()))
		);
	}
}
