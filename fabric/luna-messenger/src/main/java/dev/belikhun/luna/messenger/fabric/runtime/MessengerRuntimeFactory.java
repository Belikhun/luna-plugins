package dev.belikhun.luna.messenger.fabric.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.messenger.fabric.placeholder.BackendPlaceholders;
import net.minecraft.server.level.ServerPlayer;

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
	public static FabricMessengerRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		PluginMessageBus<ServerPlayer, ServerPlayer> bus =
			(PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager.resolveOptional(PluginMessageBus.class).orElse(null);

		if (bus == null) {
			logger.warn("Thiếu PluginMessageBus, messenger sẽ chạy ở chế độ no-op.");
			return new NoopMessengerRuntime();
		}

		BackendPlaceholderResolver placeholderResolver = new BackendPlaceholders(dependencyManager);
		BackendIdentity backendIdentity = dependencyManager.resolveOptional(BackendIdentity.class)
			.orElseGet(() -> () -> new BackendMetadata("backend", "", "").sanitize());

		return new PresenceTrackingMessengerRuntime(logger, bus, placeholderResolver, backendIdentity);
	}
}
