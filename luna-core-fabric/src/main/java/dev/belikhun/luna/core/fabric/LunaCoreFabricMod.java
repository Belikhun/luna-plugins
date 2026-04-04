package dev.belikhun.luna.core.fabric;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.config.FabricCoreConfig;
import dev.belikhun.luna.core.fabric.lifecycle.FabricModBootstrap;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LunaCoreFabricMod implements DedicatedServerModInitializer {
	private static final AtomicBoolean STARTED = new AtomicBoolean(false);
	private static final LunaCoreFabricRuntime RUNTIME = new LunaCoreFabricRuntime();
	private static final AtomicReference<FabricVersionFamily> ACTIVE_FAMILY = new AtomicReference<>();
	private static volatile LunaLogger logger = FabricModBootstrap.initLogger("luna-core-fabric", "CoreFabric", false);
	private static volatile FabricCoreConfig config = new FabricCoreConfig(false, false, dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig.disabled());

	@Override
	public void onInitializeServer() {
		start();
	}

	public static LunaCoreFabricRuntime runtime() {
		if (!STARTED.get()) {
			throw new IllegalStateException("LunaCore Fabric runtime has not been initialized yet.");
		}

		return RUNTIME;
	}

	public static FabricVersionFamily family() {
		FabricVersionFamily family = ACTIVE_FAMILY.get();
		if (family == null) {
			throw new IllegalStateException("LunaCore Fabric runtime did not resolve an active family.");
		}
		return family;
	}

	private static void start() {
		if (!STARTED.compareAndSet(false, true)) {
			return;
		}

		Path configPath = FabricModBootstrap.ensureConfigFile("luna-core-fabric.toml", FabricCoreConfig.defaultToml(), logger);
		try {
			config = FabricCoreConfig.load(configPath);
			logger = FabricModBootstrap.initLogger("luna-core-fabric", "CoreFabric", config.debugLogging());
		} catch (Exception exception) {
			logger.error("Không thể đọc luna-core-fabric.toml. Sử dụng cấu hình mặc định.", exception);
		}

		FabricVersionFamily family = FabricRuntimeEnvironment.detectCurrentFamily();
		ACTIVE_FAMILY.set(family);
		RUNTIME.updateAmqpConfig(config.amqp());
		RUNTIME.start(family);
		logger.success("LunaCore Fabric đã khởi động cho family " + family.id());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
	}

	private static void stop() {
		FabricVersionFamily family = ACTIVE_FAMILY.getAndSet(null);
		if (family == null) {
			return;
		}

		RUNTIME.stop(family);
		logger.audit("LunaCore Fabric đã tắt cho family " + family.id());
		STARTED.set(false);
	}

	public static FabricCoreConfig config() {
		return config;
	}

	public static LunaLogger logger() {
		return logger;
	}
}
