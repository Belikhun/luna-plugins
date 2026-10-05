package dev.belikhun.luna.messenger.mc.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class MessengerRuntimeFactory {
	/**
	 * Where the player is and how they are doing, sent with every chat line so a
	 * listener on the proxy (the chat bridge's AI bot) knows the speaker's
	 * situation. LunaCore answers these on every mod loader with the same names
	 * Paper's PlaceholderAPI Player expansion uses.
	 */
	private static final java.util.List<String> STATUS_EXPORT_KEYS = java.util.List.of(
		"player_world",
		"player_x",
		"player_y",
		"player_z",
		"player_health_rounded",
		"player_max_health_rounded",
		"player_food_level",
		"player_level",
		"player_gamemode",
		"player_biome"
	);

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
	public static MessengerRuntime create(LunaLogger logger, MinecraftServer server, DependencyManager dependencyManager) {
		PluginMessageBus<ServerPlayer, ServerPlayer> bus =
			(PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager.resolveOptional(PluginMessageBus.class).orElse(null);

		if (bus == null) {
			logger.warn("Thiếu PluginMessageBus, messenger sẽ chạy ở chế độ no-op.");
			return new NoopMessengerRuntime();
		}

		BackendPlaceholderResolver placeholderResolver = new CoreBackedPlaceholderResolver(dependencyManager, STATUS_EXPORT_KEYS);
		BackendIdentity backendIdentity = dependencyManager.resolveOptional(BackendIdentity.class)
			.orElseGet(() -> () -> new BackendMetadata("backend", "", "").sanitize());

		return new PresenceTrackingMessengerRuntime(logger, server, bus, placeholderResolver, backendIdentity);
	}
}
