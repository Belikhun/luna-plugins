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
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
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
		boolean pluginMessagingLogsEnabled = ConfigValues.booleanValue(pluginMessagingConfig, "enabled", false);
		long heartbeatTimeoutMillis = Math.max(1000L, ConfigValues.intValue(heartbeatConfig, "timeoutSeconds", 20) * 1000L);
		String forwardingSecret = VelocityForwardingSecretResolver.resolve(dataDirectory, logger.scope("Heartbeat"));
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
		dependencyManager.registerSingleton(ProxyServer.class, proxyServer);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(VelocityHttpServerManager.class, httpServerManager);
		dependencyManager.registerSingleton(VelocityPluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(BackendStatusView.class, backendStatusRegistry);
		dependencyManager.registerSingleton(BackendHeartbeatEventEmitter.class, backendStatusRegistry);
		dependencyManager.registerSingleton(VelocityBackendStatusRegistry.class, backendStatusRegistry);
		dependencyManager.registerSingleton(LuckPermsService.class, new LuckPermsService());
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		LunaCoreVelocity.set(new LunaCoreVelocityServices(this, proxyServer, logger, dependencyManager, httpServerManager, pluginMessagingBus, backendStatusRegistry, backendStatusRegistry));
		registerCommands();
		httpServerManager.startIfEnabled(dataDirectory.resolve("config.yml"));
		logger.success("LunaCore (Velocity) đã khởi động thành công.");
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
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
}

