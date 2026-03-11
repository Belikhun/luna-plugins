package dev.belikhun.luna.core.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
		Map<String, Object> loggingConfig = readMap(rootConfig, "logging");
		Map<String, Object> pluginMessagingConfig = readMap(loggingConfig, "pluginMessaging");
		boolean pluginMessagingLogsEnabled = readBoolean(pluginMessagingConfig, "enabled", false);
		pluginMessagingBus = new VelocityPluginMessagingBus(proxyServer, this, logger, pluginMessagingLogsEnabled);
		dependencyManager.registerSingleton(ProxyServer.class, proxyServer);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(VelocityHttpServerManager.class, httpServerManager);
		dependencyManager.registerSingleton(VelocityPluginMessagingBus.class, pluginMessagingBus);
		dependencyManager.registerSingleton(LuckPermsService.class, new LuckPermsService());
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		LunaCoreVelocity.set(new LunaCoreVelocityServices(this, proxyServer, logger, dependencyManager, httpServerManager, pluginMessagingBus));
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

	private Map<String, Object> readMap(Map<String, Object> source, String key) {
		if (source == null) {
			return Map.of();
		}

		Object value = source.get(key);
		if (!(value instanceof Map<?, ?> nested)) {
			return Map.of();
		}

		Map<String, Object> output = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : nested.entrySet()) {
			output.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return output;
	}

	private boolean readBoolean(Map<String, Object> source, String key, boolean fallback) {
		Object value = source.get(key);
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value == null) {
			return fallback;
		}

		String text = String.valueOf(value).trim().toLowerCase();
		if (text.equals("true") || text.equals("yes") || text.equals("1")) {
			return true;
		}
		if (text.equals("false") || text.equals("no") || text.equals("0")) {
			return false;
		}
		return fallback;
	}
}

