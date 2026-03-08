package dev.belikhun.luna.core.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import dev.belikhun.luna.core.api.logging.LunaLogger;

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

	@Inject
	public LunaCoreVelocityPlugin() {
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaCoreVelocity"), true).scope("CoreVelocity");
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		logger.success("LunaCore (Velocity) đã khởi động thành công.");
	}
}

