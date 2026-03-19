package dev.belikhun.luna.countdown.fabric;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.compat.FabricCompatibilityDiagnostics;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.countdown.fabric.binding.FabricCountdownFamilyBinding;
import dev.belikhun.luna.countdown.fabric.binding.NoopFabricCountdownFamilyBinding;
import dev.belikhun.luna.countdown.fabric.service.FabricCountdownCommandService;
import dev.belikhun.luna.countdown.fabric.service.FabricCountdownService;

import java.util.logging.Logger;

public final class LunaCountdownFabricRuntime {

	private final LunaCoreFabricRuntime coreRuntime;
	private final LunaLogger logger;
	private FabricPluginMessagingBus pluginMessagingBus;
	private FabricCountdownService countdownService;
	private FabricCountdownCommandService commandService;
	private FabricCountdownFamilyBinding familyBinding;

	public LunaCountdownFabricRuntime(LunaCoreFabricRuntime coreRuntime) {
		this.coreRuntime = coreRuntime;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaCountdownFabric"), true);
	}

	public void enable(FabricVersionFamily family) {
		coreRuntime.start(family);
		FabricCompatibilityDiagnostics.logSnapshot(logger.scope("Compat"), FabricCompatibilityDiagnostics.scan());
		pluginMessagingBus = coreRuntime.createPluginMessagingBus(logger.scope("Messaging"), false);
		countdownService = new FabricCountdownService(logger);
		commandService = new FabricCountdownCommandService(countdownService);
		familyBinding = loadFamilyBinding(family);
		familyBinding.bind(this, family);
		logger.success("LunaCountdown Fabric runtime đã khởi động cho family " + family.id());
	}

	public void disable(FabricVersionFamily family) {
		if (countdownService != null) {
			countdownService.close();
			countdownService = null;
		}
		commandService = null;
		if (familyBinding != null) {
			familyBinding.unbind(this, family);
			familyBinding = null;
		}
		if (pluginMessagingBus != null) {
			pluginMessagingBus.close();
			pluginMessagingBus = null;
		}
		coreRuntime.stop(family);
		logger.audit("LunaCountdown Fabric runtime đã tắt cho family " + family.id());
	}

	public FabricCountdownService countdownService() {
		return countdownService;
	}

	public FabricCountdownCommandService commandService() {
		return commandService;
	}

	private FabricCountdownFamilyBinding loadFamilyBinding(FabricVersionFamily family) {
		String className = switch (family) {
			case MC1165 -> "dev.belikhun.luna.countdown.fabric.binding.mc1165.Mc1165CountdownBinding";
			case MC1182 -> "dev.belikhun.luna.countdown.fabric.binding.mc1182.Mc1182CountdownBinding";
			case MC119X -> "dev.belikhun.luna.countdown.fabric.binding.mc119x.Mc119xCountdownBinding";
			case MC1201 -> "dev.belikhun.luna.countdown.fabric.binding.mc1201.Mc1201CountdownBinding";
			case MC121X -> "dev.belikhun.luna.countdown.fabric.binding.mc121x.Mc121xCountdownBinding";
		};

		try {
			Class<?> bindingClass = Class.forName(className);
			Object instance = bindingClass.getDeclaredConstructor().newInstance();
			if (instance instanceof FabricCountdownFamilyBinding binding) {
				return binding;
			}
		} catch (ReflectiveOperationException ignored) {
			logger.warn("Chưa có binding countdown cho family " + family.id() + ", dùng noop binding.");
		}

		return new NoopFabricCountdownFamilyBinding();
	}
}
