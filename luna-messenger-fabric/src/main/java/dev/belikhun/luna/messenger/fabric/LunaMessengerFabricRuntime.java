package dev.belikhun.luna.messenger.fabric;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.compat.FabricCompatibilityDiagnostics;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.messenger.fabric.binding.command.FabricMessengerCommandBindingSupport;
import dev.belikhun.luna.messenger.fabric.binding.event.FabricMessengerChatBindingSupport;
import dev.belikhun.luna.messenger.fabric.service.FabricBackendPlaceholderResolver;
import dev.belikhun.luna.messenger.fabric.service.FabricMessengerCommandService;
import dev.belikhun.luna.messenger.fabric.service.FabricMessengerGateway;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class LunaMessengerFabricRuntime {
	private static final long TIMEOUT_MONITOR_INTERVAL_MILLIS = 1000L;

	private final LunaCoreFabricRuntime coreRuntime;
	private final LunaLogger logger;
	private FabricPluginMessagingBus pluginMessagingBus;
	private FabricMessengerGateway gateway;
	private FabricMessengerCommandService commandService;
	private ScheduledExecutorService timeoutScheduler;
	private ScheduledFuture<?> timeoutMonitorTask;

	public LunaMessengerFabricRuntime(LunaCoreFabricRuntime coreRuntime) {
		this.coreRuntime = coreRuntime;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaMessengerFabric"), true);
	}

	public void enable(FabricVersionFamily family) {
		coreRuntime.start(family);
		FabricCompatibilityDiagnostics.logSnapshot(logger.scope("Compat"), FabricCompatibilityDiagnostics.scan());
		pluginMessagingBus = coreRuntime.createPluginMessagingBus(logger.scope("Messaging"), false);
		gateway = new FabricMessengerGateway(
			logger,
			pluginMessagingBus,
			new FabricBackendPlaceholderResolver(coreRuntime::currentServer),
			6000L,
			true
		);
		gateway.registerChannels();
		startTimeoutMonitor();
		commandService = new FabricMessengerCommandService(gateway);
		FabricMessengerCommandBindingSupport.register(commandService);
		FabricMessengerChatBindingSupport.register(commandService);
		logger.success("LunaMessenger Fabric runtime đã khởi động cho family " + family.id());
	}

	public void disable(FabricVersionFamily family) {
		stopTimeoutMonitor();
		if (gateway != null) {
			gateway.close();
			gateway = null;
		}
		commandService = null;
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
		coreRuntime.stop(family);
		logger.audit("LunaMessenger Fabric runtime đã tắt cho family " + family.id());
	}

	public FabricMessengerGateway gateway() {
		return gateway;
	}

	public FabricMessengerCommandService commandService() {
		return commandService;
	}

	private void startTimeoutMonitor() {
		stopTimeoutMonitor();
		timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "luna-messenger-timeout-monitor");
			thread.setDaemon(true);
			return thread;
		});
		timeoutMonitorTask = timeoutScheduler.scheduleAtFixedRate(
			this::checkRequestTimeouts,
			TIMEOUT_MONITOR_INTERVAL_MILLIS,
			TIMEOUT_MONITOR_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	private void stopTimeoutMonitor() {
		if (timeoutMonitorTask != null) {
			timeoutMonitorTask.cancel(false);
			timeoutMonitorTask = null;
		}
		if (timeoutScheduler != null) {
			timeoutScheduler.shutdownNow();
			timeoutScheduler = null;
		}
	}

	private void checkRequestTimeouts() {
		FabricMessengerGateway currentGateway = gateway;
		if (currentGateway == null) {
			return;
		}

		Collection<FabricMessengerGateway.PendingRequest> timedOut = currentGateway.collectTimedOutRequests(System.currentTimeMillis());
		if (timedOut.isEmpty()) {
			return;
		}

		for (FabricMessengerGateway.PendingRequest pending : timedOut) {
			logger.warn("Messenger request timeout: reqId=" + pending.requestId()
				+ " playerId=" + pending.playerId()
				+ " command=" + pending.commandType().name());
		}
	}
}
