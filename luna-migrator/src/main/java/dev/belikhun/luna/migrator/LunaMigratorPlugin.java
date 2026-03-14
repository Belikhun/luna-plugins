package dev.belikhun.luna.migrator;

import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.migrator.command.MigrationCommand;
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

		MigrationStateRepository stateRepository = new MigrationStateRepository(LunaCore.services().databaseManager().getDatabase());
		stateRepository.ensureSchema();
		MigrationCommand migrationCommand = new MigrationCommand(stateRepository);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			commands.registrar().register("migrate", migrationCommand);
			commands.registrar().register("migration", migrationCommand);
		});
		getLogger().info("LunaMigrator đã khởi động.");
	}
}
