package dev.belikhun.luna.countdown.fabric;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.logging.Logger;

public final class LunaCountdownFabricRuntime {

	private final LunaCoreFabricRuntime coreRuntime;
	private final LunaLogger logger;
	private FabricPluginMessagingBus pluginMessagingBus;

	public LunaCountdownFabricRuntime(LunaCoreFabricRuntime coreRuntime) {
		this.coreRuntime = coreRuntime;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaCountdownFabric"), true);
	}

	public void enable(FabricVersionFamily family) {
		coreRuntime.start(family);
		pluginMessagingBus = coreRuntime.createPluginMessagingBus(logger.scope("Messaging"), false);
		// TODO: Register countdown commands/events for selected family.
	}

	public void disable(FabricVersionFamily family) {
		// TODO: Unregister countdown commands/events for selected family.
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
		coreRuntime.stop(family);
	}
}
