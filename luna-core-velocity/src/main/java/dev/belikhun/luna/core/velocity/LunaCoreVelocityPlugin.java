package dev.belikhun.luna.core.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
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
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfigCodec;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.velocity.command.LunaCoreVelocityAdminCommand;
import dev.belikhun.luna.core.velocity.command.VelocityServerConnectCommand;
import dev.belikhun.luna.core.velocity.command.VelocityServersCommand;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendNameResolver;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityForwardingSecretResolver;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityHeartbeatHttpEndpoints;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.core.velocity.placeholder.VelocityLunaMiniPlaceholders;
import dev.belikhun.luna.core.velocity.serverselector.VelocitySelectorServerDisplayResolver;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorValidationReport;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorValidator;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.io.InputStream;

@Plugin(
	id = "lunacore",
	name = "LunaCore",
	version = BuildConstants.VERSION,
	description = "Luna core utilities for Velocity",
	dependencies = {
		@Dependency(id = "miniplaceholders", optional = true)
	},
	authors = {"Belikhun"}
)
public final class LunaCoreVelocityPlugin {
	private static final int CURRENT_CONFIG_VERSION = 2;

	private final LunaLogger logger;
	private final Path dataDirectory;
	private VelocityHttpServerManager httpServerManager;
	private final ProxyServer proxyServer;
	private final DependencyManager dependencyManager;
	private VelocityPluginMessagingBus pluginMessagingBus;
	private VelocityBackendStatusRegistry backendStatusRegistry;
	private VelocityServerSelectorConfig serverSelectorConfig;
	private VelocityServerConnectCommand selectorConnectCommand;
	private VelocityLunaMiniPlaceholders lunaMiniPlaceholders;
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
		reloadModules();
		logger.success("LunaCore (Velocity) đã khởi động thành công.");
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		teardownRuntime();
		unregisterOwnedCommands();
		dependencyManager.clear();
		LunaCoreVelocity.clear();
	}

	public synchronized void reloadModules() {
		Path configPath = dataDirectory.resolve("config.yml");
		Path serversConfigPath = dataDirectory.resolve("servers.yml");
		Map<String, Object> rootConfig = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> selectorRootConfig = LunaYamlConfig.loadMap(serversConfigPath);
		Map<String, Object> loggingConfig = ConfigValues.map(rootConfig, "logging");
		Map<String, Object> pluginMessagingConfig = ConfigValues.map(loggingConfig, "pluginMessaging");
		Map<String, Object> heartbeatTransportLoggingConfig = ConfigValues.map(loggingConfig, "heartbeatTransport");
		Map<String, Object> heartbeatConfig = ConfigValues.map(rootConfig, "heartbeat");
		Map<String, Object> databaseConfig = ConfigValues.map(rootConfig, "database");

		VelocityServerSelectorConfig nextSelectorConfig = VelocityServerSelectorConfig.from(selectorRootConfig);
		runSelectorValidation(selectorRootConfig, nextSelectorConfig);

		boolean pluginMessagingLogsEnabled = ConfigValues.booleanValue(pluginMessagingConfig, "enabled", false);
		boolean heartbeatTransportLogsEnabled = ConfigValues.booleanValue(heartbeatTransportLoggingConfig, "enabled", false);
		long heartbeatTimeoutMillis = Math.max(1000L, ConfigValues.intValue(heartbeatConfig, "timeoutSeconds", 20) * 1000L);
		String forwardingSecret = VelocityForwardingSecretResolver.resolve(dataDirectory, logger.scope("Heartbeat"));
		AmqpMessagingConfig amqpMessagingConfig = AmqpMessagingConfigCodec.fromConfigMap(rootConfig);

		VelocityHttpServerManager nextHttpServerManager = new VelocityHttpServerManager(this.logger);
		Database nextDatabase = createSharedDatabase(databaseConfig);
		VelocityBackendStatusRegistry nextBackendStatusRegistry = new VelocityBackendStatusRegistry(heartbeatTimeoutMillis, logger);
		ServerDisplayResolver nextServerDisplayResolver = new VelocitySelectorServerDisplayResolver(nextSelectorConfig, nextBackendStatusRegistry);
		ScheduledExecutorService nextHeartbeatSweepExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-heartbeat-timeout-sweep");
			thread.setDaemon(true);
			return thread;
		});
		nextHeartbeatSweepExecutor.scheduleAtFixedRate(
			() -> nextBackendStatusRegistry.sweepTimeouts(System.currentTimeMillis()),
			1L,
			1L,
			TimeUnit.SECONDS
		);

		new VelocityHeartbeatHttpEndpoints(
			logger,
			nextBackendStatusRegistry,
			new VelocityBackendNameResolver(proxyServer),
			nextSelectorConfig,
			amqpMessagingConfig,
			forwardingSecret,
			heartbeatTransportLogsEnabled
		).register(nextHttpServerManager.router());

		VelocityPluginMessagingBus nextPluginMessagingBus = new VelocityPluginMessagingBus(proxyServer, this, logger, pluginMessagingLogsEnabled);
		nextPluginMessagingBus.updateAmqpConfig(amqpMessagingConfig);
		registerMessagingHandlers(nextPluginMessagingBus);

		teardownRuntime();

		httpServerManager = nextHttpServerManager;
		sharedDatabase = nextDatabase;
		backendStatusRegistry = nextBackendStatusRegistry;
		serverSelectorConfig = nextSelectorConfig;
		pluginMessagingBus = nextPluginMessagingBus;
		heartbeatSweepExecutor = nextHeartbeatSweepExecutor;
		selectorConnectCommand = null;

		dependencyManager.clear();
		dependencyManager.registerSingleton(ProxyServer.class, proxyServer);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(VelocityHttpServerManager.class, httpServerManager);
		dependencyManager.registerSingleton(VelocityPluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(VelocityServerSelectorConfig.class, serverSelectorConfig);
		dependencyManager.registerSingleton(ServerDisplayResolver.class, nextServerDisplayResolver);
		dependencyManager.registerSingleton(BackendStatusView.class, backendStatusRegistry);
		dependencyManager.registerSingleton(BackendHeartbeatEventEmitter.class, backendStatusRegistry);
		dependencyManager.registerSingleton(VelocityBackendStatusRegistry.class, backendStatusRegistry);
		dependencyManager.registerSingleton(Database.class, sharedDatabase);
		dependencyManager.registerSingleton(LuckPermsService.class, new LuckPermsService());
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		LunaCoreVelocity.set(new LunaCoreVelocityServices(this, proxyServer, logger, dependencyManager, httpServerManager, pluginMessagingBus, backendStatusRegistry, backendStatusRegistry));

		unregisterOwnedCommands();
		registerCommands(serverSelectorConfig);
		registerMiniPlaceholders(nextServerDisplayResolver);
		httpServerManager.startIfEnabled(configPath);
		logger.success("Đã reload LunaCore Velocity: config và modules đã được khởi tạo lại.");
	}

	private void ensureDefaults() {
		Path config = dataDirectory.resolve("config.yml");
		Path servers = dataDirectory.resolve("servers.yml");
		try {
			LunaYamlConfig.ensureFile(config, () -> getClass().getClassLoader().getResourceAsStream("config.yml"));
			migrateConfig(config);
			boolean migrated = migrateServerSelectorConfig(config, servers);
			if (!migrated && !Files.exists(servers)) {
				LunaYamlConfig.ensureFile(servers, () -> getClass().getClassLoader().getResourceAsStream("servers.yml"));
			}
			logger.audit("Đã sẵn sàng tệp cấu hình: " + config + ", " + servers);
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo config.yml mặc định cho LunaCore Velocity.", exception);
		}
	}

	private void migrateConfig(Path configPath) {
		try {
			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(configPath));
			int version = ConfigValues.intValue(current, "configVersion", 0);
			boolean changed = false;

			while (version < CURRENT_CONFIG_VERSION) {
				int nextVersion = version + 1;
				switch (nextVersion) {
					case 1 -> migrateConfigV1(current);
					case 2 -> migrateConfigV2(current);
					default -> throw new IllegalStateException("Unknown LunaCore Velocity config migration version: " + nextVersion);
				}
				version = nextVersion;
				current.put("configVersion", version);
				changed = true;
				logger.audit("Đã áp dụng LunaCore Velocity config migration v" + version + ".");
			}

			if (!current.containsKey("configVersion")) {
				current.put("configVersion", CURRENT_CONFIG_VERSION);
				changed = true;
			}

			if (changed) {
				LunaYamlConfig.dumpMap(configPath, current);
				logger.success("Đã migrate config.yml theo configVersion=" + CURRENT_CONFIG_VERSION + ".");
			}
		} catch (RuntimeException exception) {
			logger.warn("Không thể migrate config.yml tự động: " + exception.getMessage());
		}
	}

	private void migrateConfigV1(Map<String, Object> rootConfig) {
		ensureDefault(rootConfig, "database.enabled", true);
		ensureDefault(rootConfig, "database.type", "mariadb");
		ensureDefault(rootConfig, "database.host", "127.0.0.1");
		ensureDefault(rootConfig, "database.port", 3306);
		ensureDefault(rootConfig, "database.name", "luna");
		ensureDefault(rootConfig, "database.username", "root");
		ensureDefault(rootConfig, "database.password", "");
		ensureDefault(rootConfig, "database.options.useSSL", false);
		ensureDefault(rootConfig, "database.options.serverTimezone", "UTC");
		ensureDefault(rootConfig, "http.enabled", true);
		ensureDefault(rootConfig, "http.host", "0.0.0.0");
		ensureDefault(rootConfig, "http.port", 32452);
		ensureDefault(rootConfig, "http.pathPrefix", "/api");
		ensureDefault(rootConfig, "heartbeat.timeoutSeconds", 20);
		ensureDefault(rootConfig, "diagnostics.selector.enabled", true);
		ensureDefault(rootConfig, "diagnostics.selector.fail-on-validation-error", true);
		ensureDefault(rootConfig, "diagnostics.selector.unknown-placeholder-as-error", false);
		ensureDefault(rootConfig, "logging.ansi", true);
		ensureDefault(rootConfig, "logging.defaultScope", "LunaCoreVelocity");
		ensureDefault(rootConfig, "logging.level", "INFO");
		ensureDefault(rootConfig, "logging.pluginMessaging.enabled", false);
		ensureDefault(rootConfig, "messaging.rabbitmq.enabled", false);
		ensureDefault(rootConfig, "messaging.rabbitmq.uri", "amqp://guest:guest@127.0.0.1:5672/%2F");
		ensureDefault(rootConfig, "messaging.rabbitmq.exchange", "luna.plugin-messaging");
		ensureDefault(rootConfig, "messaging.rabbitmq.proxyQueue", "luna.proxy.messaging");
		ensureDefault(rootConfig, "messaging.rabbitmq.backendQueuePrefix", "luna.backend.");
		ensureDefault(rootConfig, "messaging.rabbitmq.connectionTimeoutMillis", 5000);
		ensureDefault(rootConfig, "messaging.rabbitmq.requestedHeartbeatSeconds", 15);
	}

	private void migrateConfigV2(Map<String, Object> rootConfig) {
		ensureDefault(rootConfig, "logging.heartbeatTransport.enabled", false);
	}

	private void ensureDefault(Map<String, Object> rootConfig, String path, Object value) {
		if (rootConfig == null || path == null || path.isBlank()) {
			return;
		}

		String[] parts = path.split("\\.");
		Map<String, Object> current = rootConfig;
		for (int index = 0; index < parts.length - 1; index++) {
			String part = parts[index];
			Object next = current.get(part);
			if (next instanceof Map<?, ?> nextMap) {
				current = castMap(nextMap);
				continue;
			}

			Map<String, Object> created = new LinkedHashMap<>();
			current.put(part, created);
			current = created;
		}

		current.putIfAbsent(parts[parts.length - 1], value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	private boolean migrateServerSelectorConfig(Path configPath, Path serversPath) {
		Map<String, Object> rootConfig = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> serversConfig = new LinkedHashMap<>(LunaYamlConfig.loadMap(serversPath));

		Map<String, Object> legacySelector = ConfigValues.map(rootConfig, "server-selector");
		Map<String, Object> currentSelector = ConfigValues.map(serversConfig, "server-selector");
		if (legacySelector.isEmpty() || !currentSelector.isEmpty()) {
			return false;
		}

		serversConfig.put("server-selector", deepCopy(legacySelector));
		LunaYamlConfig.dumpMap(serversPath, serversConfig);
		logger.success("Đã migrate server-selector từ config.yml sang servers.yml.");
		return true;
	}

	private Object deepCopy(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copied = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				copied.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
			}
			return copied;
		}
		if (value instanceof List<?> list) {
			List<Object> copied = new ArrayList<>();
			for (Object item : list) {
				copied.add(deepCopy(item));
			}
			return copied;
		}
		return value;
	}

	private void registerCommands(VelocityServerSelectorConfig selectorConfig) {
		CommandManager manager = proxyServer.getCommandManager();
		CommandMeta meta = manager.metaBuilder("lunacoreproxy")
			.aliases("lcv", "luna")
			.build();
		manager.register(meta, new LunaCoreVelocityAdminCommand(backendStatusRegistry, this::reloadModules));

		if (!selectorConfig.enabled()) {
			logger.warn("Server selector đang tắt trong cấu hình Velocity.");
			return;
		}

		pluginMessagingBus.registerOutgoing(CoreServerSelectorMessageChannels.OPEN_MENU);
		selectorConnectCommand = new VelocityServerConnectCommand(proxyServer, backendStatusRegistry, pluginMessagingBus, selectorConfig, true);

		try {
			CommandMeta serverMeta = manager.metaBuilder("server").build();
			manager.register(serverMeta, selectorConnectCommand);
			if (selectorConfig.diagnostics().enabled()) {
				logger.audit("Selector diagnostics: /server override mode=OVERRIDDEN");
			}
		} catch (Exception exception) {
			if (selectorConfig.failOnServerOverrideFailure()) {
				throw new IllegalStateException("Không thể override lệnh /server. Dừng khởi động theo cấu hình fail-fast.", exception);
			}
			logger.warn("Không thể override lệnh /server: " + exception.getMessage());
			if (selectorConfig.diagnostics().enabled()) {
				logger.warn("Selector diagnostics: /server override mode=FALLBACK_DISABLED (/connect + /servers vẫn hoạt động).");
			}
		}

		CommandMeta connectMeta = manager.metaBuilder("connect").build();
		manager.register(connectMeta, new VelocityServerConnectCommand(proxyServer, backendStatusRegistry, pluginMessagingBus, selectorConfig, false));

		CommandMeta serversMeta = manager.metaBuilder("servers").build();
		manager.register(serversMeta, new VelocityServersCommand(pluginMessagingBus, selectorConfig));
	}

	private void runSelectorValidation(Map<String, Object> rootConfig, VelocityServerSelectorConfig selectorConfig) {
		VelocityServerSelectorValidationReport report = VelocityServerSelectorValidator.validate(rootConfig, selectorConfig);

		if (report.hasWarnings()) {
			for (String warning : report.warnings()) {
				logger.warn("[SelectorValidation] " + warning);
			}
		}

		if (!report.hasErrors()) {
			if (selectorConfig.diagnostics().enabled()) {
				logger.audit("Selector validation hoàn tất: không có lỗi cấu hình.");
			}
			return;
		}

		for (String error : report.errors()) {
			logger.error("[SelectorValidation] " + error);
		}

		if (selectorConfig.enabled() && selectorConfig.diagnostics().failOnValidationError()) {
			throw new IllegalStateException("Phát hiện lỗi cấu hình server-selector. Dừng khởi động theo diagnostics.fail-on-validation-error=true.");
		}
	}

	private void registerMiniPlaceholders(ServerDisplayResolver serverDisplayResolver) {
		if (proxyServer.getPluginManager().getPlugin("miniplaceholders").isEmpty()) {
			logger.audit("MiniPlaceholders chưa được cài trên proxy. Bỏ qua namespace luna.");
			return;
		}

		try {
			lunaMiniPlaceholders = new VelocityLunaMiniPlaceholders(logger, backendStatusRegistry, serverSelectorConfig, serverDisplayResolver);
			lunaMiniPlaceholders.register();
		} catch (Throwable throwable) {
			logger.warn("Không thể đăng ký MiniPlaceholders namespace luna: " + throwable.getMessage());
			lunaMiniPlaceholders = null;
		}
	}

	private void registerMessagingHandlers(VelocityPluginMessagingBus bus) {
		bus.registerIncoming(CorePlayerMessageChannels.CHAT_RELAY, context -> {
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

		bus.registerIncoming(CoreServerSelectorMessageChannels.CONNECT_REQUEST, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String playerIdRaw = reader.readUtf();
			String backendName = reader.readUtf();
			if (selectorConnectCommand == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			try {
				java.util.UUID playerId = java.util.UUID.fromString(playerIdRaw);
				proxyServer.getPlayer(playerId).ifPresent(player -> selectorConnectCommand.connectByName(player, backendName));
			} catch (IllegalArgumentException ignored) {
			}

			return PluginMessageDispatchResult.HANDLED;
		});
	}

	private void unregisterOwnedCommands() {
		CommandManager manager = proxyServer.getCommandManager();
		for (String alias : List.of("lunacoreproxy", "lcv", "luna", "server", "connect", "servers")) {
			try {
				manager.unregister(alias);
			} catch (Exception ignored) {
			}
		}
	}

	private void teardownRuntime() {
		if (lunaMiniPlaceholders != null) {
			lunaMiniPlaceholders.unregister();
			lunaMiniPlaceholders = null;
		}
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
		if (heartbeatSweepExecutor != null) {
			heartbeatSweepExecutor.shutdownNow();
			heartbeatSweepExecutor = null;
		}
		if (httpServerManager != null) {
			httpServerManager.stop();
		}
		if (sharedDatabase != null) {
			sharedDatabase.close();
			sharedDatabase = null;
		}
		selectorConnectCommand = null;
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

