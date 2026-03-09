package dev.belikhun.luna.core.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;

import java.nio.file.Path;
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
	private VelocityPluginMessagingBus pluginMessagingBus;

	@Inject
	public LunaCoreVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaCoreVelocity"), true).scope("CoreVelocity");
		this.dataDirectory = dataDirectory;
		this.httpServerManager = new VelocityHttpServerManager(this.logger);
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		pluginMessagingBus = new VelocityPluginMessagingBus(proxyServer, this, logger);
		httpServerManager.startIfEnabled(dataDirectory.resolve("config.yml"));
		logger.success("LunaCore (Velocity) đã khởi động thành công.");
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
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
}

