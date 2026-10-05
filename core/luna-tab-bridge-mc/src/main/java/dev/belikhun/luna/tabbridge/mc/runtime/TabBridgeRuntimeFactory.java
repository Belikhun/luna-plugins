package dev.belikhun.luna.tabbridge.mc.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.messaging.mc.PluginMessagingBus;

/** Builds the TAB bridge runtime this mod ships. */
public final class TabBridgeRuntimeFactory {
	private TabBridgeRuntimeFactory() {
	}

	/**
	 * @param dependencyManager where the messaging bus, permissions and the player
	 *                          state source come from
	 * @return the raw-channel runtime, or a no-op one when the bus is missing
	 */
	public static TabBridgeRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		LunaLogger runtimeLogger = logger.scope("Runtime");
		PluginMessagingBus bus = dependencyManager.resolveOptional(PluginMessagingBus.class).orElse(null);

		if (bus == null) {
			runtimeLogger.warn("Thiếu PluginMessagingBus, fallback sang TAB bridge runtime no-op.");
			return new NoopTabBridgeRuntime();
		}

		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		TabBridgePlayerStateSource playerStateSource = dependencyManager.resolveOptional(TabBridgePlayerStateSource.class)
			.orElseGet(NoopTabBridgePlayerStateSource::new);

		return new RawChannelTabBridgeRuntime(runtimeLogger, bus, permissionService, playerStateSource);
	}
}
