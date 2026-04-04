package dev.belikhun.luna.messenger.fabric;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricMod;
import dev.belikhun.luna.core.fabric.lifecycle.FabricModBootstrap;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LunaMessengerFabricMod implements DedicatedServerModInitializer {
	private static final AtomicBoolean STARTED = new AtomicBoolean(false);
	private static volatile LunaLogger logger = FabricModBootstrap.initLogger("luna-messenger-fabric", "MessengerFabric", false);
	private static LunaMessengerFabricRuntime runtime;

	@Override
	public void onInitializeServer() {
		if (!STARTED.compareAndSet(false, true)) {
			return;
		}

		Path configPath = FabricModBootstrap.ensureConfigFile("luna-messenger-fabric.toml", FabricMessengerConfig.defaultToml(), logger);
		FabricMessengerConfig config = new FabricMessengerConfig(false, false, 6000, java.util.List.of());
		try {
			config = FabricMessengerConfig.load(configPath);
			logger = FabricModBootstrap.initLogger("luna-messenger-fabric", "MessengerFabric", config.debugLogging());
		} catch (Exception exception) {
			logger.error("Không thể đọc luna-messenger-fabric.toml. Sử dụng cấu hình mặc định.", exception);
		}

		FabricVersionFamily family = LunaCoreFabricMod.family();
		runtime = new LunaMessengerFabricRuntime(
			LunaCoreFabricMod.runtime(),
			logger,
			false,
			config.requestTimeoutMillis(),
			config.messagingDebugLogging(),
			config.placeholderExportKeys()
		);
		runtime.enable(family);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop(family));
	}

	private static void stop(FabricVersionFamily family) {
		if (!STARTED.compareAndSet(true, false)) {
			return;
		}

		if (runtime != null) {
			runtime.disable(family);
			runtime = null;
		}
	}
}
