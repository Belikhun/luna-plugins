package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;

public final class BuiltInNeoForgeTabBridgeRuntimeProvider implements NeoForgeTabBridgeRuntimeProvider {
	@Override
	public String name() {
		return "builtin";
	}

	@Override
	public int priority() {
		return 100;
	}

	@Override
	public NeoForgeTabBridgeRuntime createRuntime(LunaLogger logger, DependencyManager dependencyManager) {
		NeoForgePluginMessagingBus bus = dependencyManager.resolveOptional(NeoForgePluginMessagingBus.class).orElse(null);
		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		NeoForgeTabBridgePlayerStateSource playerStateSource = dependencyManager.resolveOptional(NeoForgeTabBridgePlayerStateSource.class)
			.orElseGet(NoopNeoForgeTabBridgePlayerStateSource::new);
		if (bus == null) {
			logger.warn("Thiếu NeoForgePluginMessagingBus, fallback sang TAB bridge runtime no-op.");
			return new NoopNeoForgeTabBridgeRuntime();
		}

		return new RawChannelNeoForgeTabBridgeRuntime(logger, bus, permissionService, playerStateSource);
	}
}
