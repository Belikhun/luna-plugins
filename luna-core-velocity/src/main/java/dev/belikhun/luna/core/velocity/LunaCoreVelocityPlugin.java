package dev.belikhun.luna.core.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

@Plugin(
	id = "lunacore",
	name = "LunaCore",
	version = BuildConstants.VERSION,
	description = "Luna core utilities for Velocity",
	authors = {"Belikhun"}
)
public final class LunaCoreVelocityPlugin {
	private final Logger logger;

	@Inject
	public LunaCoreVelocityPlugin(Logger logger) {
		this.logger = logger;
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		logger.info("LunaCore (Velocity) da khoi dong thanh cong.");
	}
}

