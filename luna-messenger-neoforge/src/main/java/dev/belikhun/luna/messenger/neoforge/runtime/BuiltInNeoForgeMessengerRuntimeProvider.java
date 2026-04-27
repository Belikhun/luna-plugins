package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;
import dev.belikhun.luna.tabbridge.neoforge.runtime.NeoForgeTabBridgeRuntime;

public final class BuiltInNeoForgeMessengerRuntimeProvider implements NeoForgeMessengerRuntimeProvider {
	@Override
	public String name() {
		return "builtin";
	}

	@Override
	public int priority() {
		return 100;
	}

	@Override
	public NeoForgeMessengerRuntime createRuntime(LunaLogger logger, DependencyManager dependencyManager, BackendPlaceholderResolver fallbackResolver) {
		NeoForgePluginMessagingBus bus = dependencyManager.resolveOptional(NeoForgePluginMessagingBus.class).orElse(null);
		if (bus == null) {
			logger.warn("Thiếu NeoForgePluginMessagingBus, fallback sang messenger runtime no-op.");
			return new NoopNeoForgeMessengerRuntime(fallbackResolver);
		}

		String localServerName = dependencyManager.resolveOptional(AmqpMessagingConfig.class)
			.map(config -> config.effectiveLocalServerName("backend"))
			.filter(value -> value != null && !value.isBlank())
			.orElse("backend");
		return new PresenceTrackingNeoForgeMessengerRuntime(logger, bus, fallbackResolver, localServerName);
	}

	@Override
	public BackendPlaceholderResolver createPlaceholderResolver(LunaLogger logger, DependencyManager dependencyManager) {
		return new TabBridgeSnapshotPlaceholderResolver(dependencyManager);
	}
}
