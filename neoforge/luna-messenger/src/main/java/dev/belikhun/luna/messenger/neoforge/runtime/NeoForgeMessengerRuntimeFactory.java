package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;

/** Builds the messenger runtime this mod ships. */
public final class NeoForgeMessengerRuntimeFactory {
	private NeoForgeMessengerRuntimeFactory() {
	}

	/**
	 * @param dependencyManager where the messaging bus and the AMQP config come from
	 * @return the presence-tracking runtime, or a no-op one when the bus is missing
	 */
	public static NeoForgeMessengerRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		LunaLogger runtimeLogger = logger.scope("Runtime");
		BackendPlaceholderResolver placeholderResolver = new CoreBackedNeoForgePlaceholderResolver(dependencyManager);
		NeoForgePluginMessagingBus bus = dependencyManager.resolveOptional(NeoForgePluginMessagingBus.class).orElse(null);

		if (bus == null) {
			runtimeLogger.warn("Thiếu NeoForgePluginMessagingBus, fallback sang messenger runtime no-op.");
			return new NoopNeoForgeMessengerRuntime(placeholderResolver);
		}

		BackendIdentity backendIdentity = dependencyManager.resolveOptional(BackendIdentity.class)
			.orElseGet(() -> () -> new BackendMetadata("backend", "", "").sanitize());

		return new PresenceTrackingNeoForgeMessengerRuntime(runtimeLogger, bus, placeholderResolver, backendIdentity);
	}
}
