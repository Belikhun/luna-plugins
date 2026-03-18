package dev.belikhun.luna.messenger.fabric;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.logging.Logger;

public final class LunaMessengerFabricRuntime {

	private final LunaCoreFabricRuntime coreRuntime;
	private final LunaLogger logger;
	private FabricPluginMessagingBus pluginMessagingBus;

	public LunaMessengerFabricRuntime(LunaCoreFabricRuntime coreRuntime) {
		this.coreRuntime = coreRuntime;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaMessengerFabric"), true);
	}

	public void enable(FabricVersionFamily family) {
		coreRuntime.start(family);
		pluginMessagingBus = coreRuntime.createPluginMessagingBus(logger.scope("Messaging"), false);
		// TODO: Register messenger channels/placeholders/commands for selected family.
	}

	public void disable(FabricVersionFamily family) {
		// TODO: Unregister messenger channels/placeholders/commands for selected family.
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
		coreRuntime.stop(family);
	}
}
