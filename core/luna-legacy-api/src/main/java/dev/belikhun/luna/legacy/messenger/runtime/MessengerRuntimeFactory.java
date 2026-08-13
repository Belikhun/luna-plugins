package dev.belikhun.luna.legacy.messenger.runtime;

import dev.belikhun.luna.legacy.dependency.DependencyManager;
import dev.belikhun.luna.legacy.heartbeat.BackendIdentity;
import dev.belikhun.luna.legacy.heartbeat.BackendMetadata;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.messenger.BackendPlaceholderResolver;

public final class MessengerRuntimeFactory {
	private MessengerRuntimeFactory() {
	}

	/**
	 * Build the runtime for this server.
	 *
	 * Without a plugin-message bus there is no proxy to talk to, so the messenger
	 * degrades to a runtime that refuses every command rather than failing to
	 * start: the commands stay registered and tell the player why.
	 */
	@SuppressWarnings("unchecked")
	public static <P> MessengerRuntime<P> create(
		LunaLogger logger,
		PlayerBridge<P> players,
		PlayerAudience<P> audience,
		DependencyManager dependencyManager
	) {
		PluginMessageBus<P, P> bus =
			(PluginMessageBus<P, P>) dependencyManager.find(PluginMessageBus.class);

		if (bus == null) {
			logger.warn("Thiếu PluginMessageBus, messenger sẽ chạy ở chế độ no-op.");
			return new NoopMessengerRuntime<P>();
		}

		BackendPlaceholderResolver placeholderResolver =
			new CoreBackedPlaceholderResolver<P>(dependencyManager, players);
		BackendIdentity resolved = dependencyManager.find(BackendIdentity.class);
		BackendIdentity backendIdentity = resolved != null
			? resolved
			: () -> new BackendMetadata("backend", "", "").sanitize();

		return new PresenceTrackingMessengerRuntime<P>(
			logger,
			players,
			audience,
			bus,
			placeholderResolver,
			backendIdentity
		);
	}
}
