package dev.belikhun.luna.countdown.fabric;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.compat.FabricCompatibilityDiagnostics;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.countdown.fabric.binding.command.FabricCountdownCommandBindingSupport;
import dev.belikhun.luna.countdown.fabric.binding.event.FabricCountdownPlayerEventBindingSupport;
import dev.belikhun.luna.countdown.fabric.service.FabricCountdownCommandService;
import dev.belikhun.luna.countdown.fabric.service.FabricCountdownService;

import java.util.logging.Logger;

public final class LunaCountdownFabricRuntime {

	private final LunaCoreFabricRuntime coreRuntime;
	private final boolean manageCoreLifecycle;
	private final boolean messagingDebugLogging;
	private final LunaLogger logger;
	private FabricPluginMessagingBus pluginMessagingBus;
	private FabricCountdownService countdownService;
	private FabricCountdownCommandService commandService;

	public LunaCountdownFabricRuntime(LunaCoreFabricRuntime coreRuntime) {
		this(coreRuntime, true);
	}

	public LunaCountdownFabricRuntime(LunaCoreFabricRuntime coreRuntime, boolean manageCoreLifecycle) {
		this(coreRuntime, LunaLogger.forLogger(Logger.getLogger("LunaCountdownFabric"), true), manageCoreLifecycle, false);
	}

	public LunaCountdownFabricRuntime(LunaCoreFabricRuntime coreRuntime, LunaLogger logger, boolean manageCoreLifecycle, boolean messagingDebugLogging) {
		this.coreRuntime = coreRuntime;
		this.manageCoreLifecycle = manageCoreLifecycle;
		this.messagingDebugLogging = messagingDebugLogging;
		this.logger = logger == null ? LunaLogger.forLogger(Logger.getLogger("LunaCountdownFabric"), true) : logger;
	}

	public void enable(FabricVersionFamily family) {
		if (manageCoreLifecycle) {
			coreRuntime.start(family);
		}
		FabricCompatibilityDiagnostics.logSnapshot(logger.scope("Compat"), FabricCompatibilityDiagnostics.scan());
		pluginMessagingBus = coreRuntime.createPluginMessagingBus(logger.scope("Messaging"), messagingDebugLogging);
		countdownService = new FabricCountdownService(logger);
		commandService = new FabricCountdownCommandService(countdownService);
		FabricCountdownCommandBindingSupport.register(commandService);
		FabricCountdownPlayerEventBindingSupport.register(countdownService);
		logger.success("LunaCountdown Fabric runtime đã khởi động cho family " + family.id());
	}

	public void disable(FabricVersionFamily family) {
		if (countdownService != null) {
			countdownService.close();
			countdownService = null;
		}
		commandService = null;
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
		if (manageCoreLifecycle) {
			coreRuntime.stop(family);
		}
		logger.audit("LunaCountdown Fabric runtime đã tắt cho family " + family.id());
	}

	public FabricCountdownService countdownService() {
		return countdownService;
	}

	public FabricCountdownCommandService commandService() {
		return commandService;
	}
}
