package dev.belikhun.luna.core;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.config.migration.ConfigStoreMigrator;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabaseManager;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.help.HelpBasicCommand;
import dev.belikhun.luna.core.api.help.HelpCommandListener;
import dev.belikhun.luna.core.api.help.HelpRegistry;
import dev.belikhun.luna.core.api.http.HttpServerManager;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LogColor;
import dev.belikhun.luna.core.api.logging.LogLevel;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.migration.MigrationManager;
import dev.belikhun.luna.core.api.profile.UserProfileRepository;
import dev.belikhun.luna.core.api.string.MessageFormatter;
import dev.belikhun.luna.core.migration.CoreConfigMigrations;
import dev.belikhun.luna.core.migration.CoreDatabaseMigrations;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaCorePlugin extends JavaPlugin {
	private LunaCoreServices services;

	@Override
	public void onEnable() {
		saveDefaultConfig();

		ConfigStore configStore = ConfigStore.of(this, "config.yml");
		boolean colorsEnabled = configStore.get("logging.ansi").asBoolean(true);
		LunaLogger logger = LunaLogger.forPlugin(this, colorsEnabled)
			.registerLevel(LogLevel.custom("BOOT", 325, LogColor.BRIGHT_CYAN));
		LunaLogger coreLogger = logger.scope("Core");
		coreLogger.log("BOOT", "Bắt đầu khởi tạo Luna Core.");

		ConfigStoreMigrator configMigrator = new ConfigStoreMigrator(configStore, logger);
		CoreConfigMigrations.register(configMigrator);
		configMigrator.migrate();
		coreLogger.success("Đã hoàn tất config migrations.");

		MessageFormatter messageFormatter = new MessageFormatter(this, logger);
		DatabaseManager databaseManager = new DatabaseManager(configStore, logger);
		databaseManager.connectFromConfig();
		DatabaseMigrator databaseMigrator = new DatabaseMigrator(databaseManager.getDatabase(), logger);
		CoreDatabaseMigrations.register(databaseMigrator);
		databaseMigrator.migrate();
		coreLogger.success("Đã hoàn tất database migrations.");
		MigrationManager migrationManager = new MigrationManager(this, configMigrator, databaseMigrator);

		HelpRegistry helpRegistry = new HelpRegistry();
		HttpServerManager httpServerManager = new HttpServerManager(this, configStore, messageFormatter, logger);
		httpServerManager.startIfEnabled();
		coreLogger.audit("HTTP manager đã sẵn sàng.");

		UserProfileRepository userProfileRepository = new UserProfileRepository(databaseManager.getDatabase());
		DependencyManager dependencyManager = new DependencyManager();
		coreLogger.audit("Đang đăng ký dependency dùng chung.");
		dependencyManager.registerSingleton(JavaPlugin.class, this);
		dependencyManager.registerSingleton(Plugin.class, this);
		dependencyManager.registerSingleton(ConfigStore.class, configStore);
		dependencyManager.registerSingleton(DatabaseManager.class, databaseManager);
		dependencyManager.registerSingleton(Database.class, databaseManager.getDatabase());
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(MessageFormatter.class, messageFormatter);
		dependencyManager.registerSingleton(HelpRegistry.class, helpRegistry);
		dependencyManager.registerSingleton(HttpServerManager.class, httpServerManager);
		dependencyManager.registerSingleton(Router.class, httpServerManager.router());
		dependencyManager.registerSingleton(UserProfileRepository.class, userProfileRepository);
		dependencyManager.registerSingleton(MigrationManager.class, migrationManager);
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		coreLogger.success("Đã đăng ký dependency container cho Luna Core.");

		services = new LunaCoreServices(
			this,
			configStore,
			databaseManager,
			dependencyManager,
			migrationManager,
			logger,
			messageFormatter,
			helpRegistry,
			httpServerManager,
			userProfileRepository
		);

		LunaCore.set(services);
		HelpCommandListener helpCommandListener = new HelpCommandListener(services);
		Bukkit.getPluginManager().registerEvents(helpCommandListener, this);
		this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands ->
			commands.registrar().register("help", new HelpBasicCommand(services, helpCommandListener))
		);
		coreLogger.audit("Help command API đã được đăng ký theo Paper Command Lifecycle.");
		coreLogger.success("Luna Core đã khởi động thành công.");
	}

	@Override
	public void onDisable() {
		LunaLogger logger = services != null ? services.logger().scope("Core") : LunaLogger.forPlugin(this, true).scope("Core");
		logger.audit("Đang tắt Luna Core.");
		if (services != null) {
			services.httpServerManager().stop();
			services.databaseManager().close();
			services.dependencyManager().clear();
		}

		LunaCore.clear();
		logger.success("Luna Core đã tắt hoàn tất.");
	}
}
