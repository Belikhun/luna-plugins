package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;

/** Builds the TAB bridge runtime this mod ships. */
public final class NeoForgeTabBridgeRuntimeFactory {
	private NeoForgeTabBridgeRuntimeFactory() {
	}

	/**
	 * @param dependencyManager where the messaging bus, permissions and the player
	 *                          state source come from
	 * @return the raw-channel runtime, or a no-op one when the bus is missing
	 */
	public static NeoForgeTabBridgeRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		LunaLogger runtimeLogger = logger.scope("Runtime");
		NeoForgePluginMessagingBus bus = dependencyManager.resolveOptional(NeoForgePluginMessagingBus.class).orElse(null);

		if (bus == null) {
			runtimeLogger.warn("Thiếu NeoForgePluginMessagingBus, fallback sang TAB bridge runtime no-op.");
			return new NoopNeoForgeTabBridgeRuntime();
		}

		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		NeoForgeTabBridgePlayerStateSource playerStateSource = dependencyManager.resolveOptional(NeoForgeTabBridgePlayerStateSource.class)
			.orElseGet(NoopNeoForgeTabBridgePlayerStateSource::new);

		return new RawChannelNeoForgeTabBridgeRuntime(runtimeLogger, bus, permissionService, playerStateSource);
	}
}
