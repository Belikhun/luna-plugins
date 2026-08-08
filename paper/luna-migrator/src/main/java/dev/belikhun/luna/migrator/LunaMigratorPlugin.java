package dev.belikhun.luna.migrator;

import dev.belikhun.luna.core.api.messaging.CorePlayerMessageChannels;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.migrator.command.MigrationCommand;
import dev.belikhun.luna.migrator.listener.MigrationJoinAlertListener;
import dev.belikhun.luna.migrator.listener.MigrationReconnectGuardListener;
import dev.belikhun.luna.migrator.service.MigrationDataTransferService;
import dev.belikhun.luna.migrator.service.MigrationEligibilityService;
import dev.belikhun.luna.migrator.service.MigrationStateRepository;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaMigratorPlugin extends JavaPlugin {
	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng. LunaMigrator sẽ tắt.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		saveDefaultConfig();
		MigrationStateRepository stateRepository = new MigrationStateRepository(LunaCore.services().databaseManager().getDatabase());
		stateRepository.ensureSchema();
		MigrationEligibilityService eligibilityService = new MigrationEligibilityService(stateRepository);
		MigrationDataTransferService dataTransferService = new MigrationDataTransferService(this);
		MigrationCommand migrationCommand = new MigrationCommand(this, stateRepository, dataTransferService, eligibilityService);
		LunaCore.services().pluginMessaging().registerOutgoing(CorePlayerMessageChannels.CHAT_RELAY);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			commands.registrar().register("migrate", migrationCommand);
		});
		getServer().getPluginManager().registerEvents(new MigrationJoinAlertListener(this, eligibilityService), this);
		getServer().getPluginManager().registerEvents(new MigrationReconnectGuardListener(migrationCommand), this);
		getLogger().info("LunaMigrator đã khởi động.");
	}

	@Override
	public void onDisable() {
		if (getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			LunaCore.services().pluginMessaging().unregisterOutgoing(CorePlayerMessageChannels.CHAT_RELAY);
		}
	}
}
