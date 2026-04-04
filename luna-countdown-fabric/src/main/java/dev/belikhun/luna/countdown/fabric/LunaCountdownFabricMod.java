package dev.belikhun.luna.countdown.fabric;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricMod;
import dev.belikhun.luna.core.fabric.lifecycle.FabricModBootstrap;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LunaCountdownFabricMod implements DedicatedServerModInitializer {
	private static final AtomicBoolean STARTED = new AtomicBoolean(false);
	private static volatile LunaLogger logger = FabricModBootstrap.initLogger("luna-countdown-fabric", "CountdownFabric", false);
	private static LunaCountdownFabricRuntime runtime;

	@Override
	public void onInitializeServer() {
		if (!STARTED.compareAndSet(false, true)) {
			return;
		}

		Path configPath = FabricModBootstrap.ensureConfigFile("luna-countdown-fabric.toml", FabricCountdownConfig.defaultToml(), logger);
		FabricCountdownConfig config = new FabricCountdownConfig(false, false);
		try {
			config = FabricCountdownConfig.load(configPath);
			logger = FabricModBootstrap.initLogger("luna-countdown-fabric", "CountdownFabric", config.debugLogging());
		} catch (Exception exception) {
			logger.error("Không thể đọc luna-countdown-fabric.toml. Sử dụng cấu hình mặc định.", exception);
		}

		FabricVersionFamily family = LunaCoreFabricMod.family();
		runtime = new LunaCountdownFabricRuntime(LunaCoreFabricMod.runtime(), logger, false, config.messagingDebugLogging());
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
