package dev.belikhun.luna.core.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.belikhun.luna.core.api.messaging.CorePlayerMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabaseConfig;
import dev.belikhun.luna.core.api.database.DatabaseType;
import dev.belikhun.luna.core.api.database.JdbcDatabase;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatEventEmitter;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.velocity.command.LunaCoreVelocityStatusCommand;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendNameResolver;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityForwardingSecretResolver;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityHeartbeatHttpEndpoints;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(
	id = "lunacore",
	name = "LunaCore",
	version = BuildConstants.VERSION,
	description = "Luna core utilities for Velocity",
	authors = {"Belikhun"}
)
public final class LunaCoreVelocityPlugin {
	private final LunaLogger logger;
	private final Path dataDirectory;
	private final VelocityHttpServerManager httpServerManager;
	private final ProxyServer proxyServer;
	private final DependencyManager dependencyManager;
	private VelocityPluginMessagingBus pluginMessagingBus;
	private VelocityBackendStatusRegistry backendStatusRegistry;
	private ScheduledExecutorService heartbeatSweepExecutor;
	private Database sharedDatabase;

	@Inject
	public LunaCoreVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaCoreVelocity"), true).scope("CoreVelocity");
		this.dataDirectory = dataDirectory;
		this.httpServerManager = new VelocityHttpServerManager(this.logger);
		this.dependencyManager = new DependencyManager();
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		Path configPath = dataDirectory.resolve("config.yml");
		Map<String, Object> rootConfig = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> loggingConfig = ConfigValues.map(rootConfig, "logging");
		Map<String, Object> pluginMessagingConfig = ConfigValues.map(loggingConfig, "pluginMessaging");
		Map<String, Object> heartbeatConfig = ConfigValues.map(rootConfig, "heartbeat");
		Map<String, Object> databaseConfig = ConfigValues.map(rootConfig, "database");
		boolean pluginMessagingLogsEnabled = ConfigValues.booleanValue(pluginMessagingConfig, "enabled", false);
		long heartbeatTimeoutMillis = Math.max(1000L, ConfigValues.intValue(heartbeatConfig, "timeoutSeconds", 20) * 1000L);
		String forwardingSecret = VelocityForwardingSecretResolver.resolve(dataDirectory, logger.scope("Heartbeat"));
		sharedDatabase = createSharedDatabase(databaseConfig);
		backendStatusRegistry = new VelocityBackendStatusRegistry(heartbeatTimeoutMillis, logger);
		heartbeatSweepExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-heartbeat-timeout-sweep");
			thread.setDaemon(true);
			return thread;
		});
		heartbeatSweepExecutor.scheduleAtFixedRate(
			() -> backendStatusRegistry.sweepTimeouts(System.currentTimeMillis()),
			1L,
			1L,
			TimeUnit.SECONDS
		);
		new VelocityHeartbeatHttpEndpoints(
			logger,
			backendStatusRegistry,
			new VelocityBackendNameResolver(proxyServer),
			forwardingSecret
		).register(httpServerManager.router());

		pluginMessagingBus = new VelocityPluginMessagingBus(proxyServer, this, logger, pluginMessagingLogsEnabled);
		pluginMessagingBus.registerIncoming(CorePlayerMessageChannels.CHAT_RELAY, context -> {
			String message = PluginMessageReader.of(context.payload()).readUtf();
			if (context.source() instanceof ServerConnection serverConnection) {
				serverConnection.getPlayer().sendRichMessage(message);
				return PluginMessageDispatchResult.HANDLED;
			}
			if (context.source() instanceof com.velocitypowered.api.proxy.Player player) {
				player.sendRichMessage(message);
			}
			return PluginMessageDispatchResult.HANDLED;
		});
		dependencyManager.registerSingleton(ProxyServer.class, proxyServer);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(VelocityHttpServerManager.class, httpServerManager);
		dependencyManager.registerSingleton(VelocityPluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(BackendStatusView.class, backendStatusRegistry);
		dependencyManager.registerSingleton(BackendHeartbeatEventEmitter.class, backendStatusRegistry);
		dependencyManager.registerSingleton(VelocityBackendStatusRegistry.class, backendStatusRegistry);
		dependencyManager.registerSingleton(Database.class, sharedDatabase);
		dependencyManager.registerSingleton(LuckPermsService.class, new LuckPermsService());
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		LunaCoreVelocity.set(new LunaCoreVelocityServices(this, proxyServer, logger, dependencyManager, httpServerManager, pluginMessagingBus, backendStatusRegistry, backendStatusRegistry));
		registerCommands();
		httpServerManager.startIfEnabled(dataDirectory.resolve("config.yml"));
		logger.success("LunaCore (Velocity) đã khởi động thành công.");
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		if (sharedDatabase != null) {
			sharedDatabase.close();
			sharedDatabase = null;
		}
		dependencyManager.clear();
		LunaCoreVelocity.clear();
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
		}
		if (heartbeatSweepExecutor != null) {
			heartbeatSweepExecutor.shutdownNow();
			heartbeatSweepExecutor = null;
		}
		httpServerManager.stop();
	}

	private void ensureDefaults() {
		Path config = dataDirectory.resolve("config.yml");
		try {
			LunaYamlConfig.ensureFile(config, () -> getClass().getClassLoader().getResourceAsStream("config.yml"));
			logger.audit("Đã sẵn sàng tệp cấu hình: " + config);
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo config.yml mặc định cho LunaCore Velocity.", exception);
		}
	}

	private void registerCommands() {
		CommandManager manager = proxyServer.getCommandManager();
		CommandMeta meta = manager.metaBuilder("lunacoreproxy")
			.aliases("lcv", "luna")
			.build();
		manager.register(meta, new LunaCoreVelocityStatusCommand(backendStatusRegistry));
	}

	private Database createSharedDatabase(Map<String, Object> databaseConfig) {
		boolean enabled = ConfigValues.booleanValue(databaseConfig, "enabled", false);
		if (!enabled) {
			logger.warn("Database đang tắt trong LunaCore Velocity config. Các plugin phụ thuộc DB sẽ dùng chế độ giới hạn.");
			return new NoopDatabase();
		}

		try {
			String configuredType = ConfigValues.string(databaseConfig, "type", "mariadb").trim().toLowerCase();
			if (!"mariadb".equals(configuredType)) {
				logger.warn("LunaCore Velocity chỉ hỗ trợ database.type=mariadb. Giá trị hiện tại: " + configuredType + ".");
				return new NoopDatabase();
			}

			DatabaseType type = DatabaseType.MARIADB;
			pinMariadbDriver();
			DatabaseConfig config = new DatabaseConfig(
				true,
				type,
				ConfigValues.string(databaseConfig, "host", "127.0.0.1"),
				ConfigValues.intValue(databaseConfig, "port", 3306),
				ConfigValues.string(databaseConfig, "name", "luna.db"),
				ConfigValues.string(databaseConfig, "username", "root"),
				ConfigValues.stringPreserveWhitespace(databaseConfig.get("password"), ""),
				ConfigValues.map(databaseConfig, "options")
			);
			Database database = new JdbcDatabase(config);
			logger.success("LunaCore Velocity đã kết nối database bằng driver " + type.name() + ".");
			return database;
		} catch (Exception exception) {
			logger.error("Không thể kết nối database cho LunaCore Velocity.", exception);
			return new NoopDatabase();
		}
	}

	private void pinMariadbDriver() {
		// Keep explicit reference so shadow minimize retains MariaDB JDBC driver.
		org.mariadb.jdbc.Driver.class.getName();
	}
}

