package dev.belikhun.luna.tabbridge.mc.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Builds the TAB bridge runtime this mod ships, on any loader. */
public final class TabBridgeRuntimeFactory {
	private TabBridgeRuntimeFactory() {
	}

	/**
	 * Build the bridge for this server.
	 *
	 * Without a plugin-message bus there is no proxy to bridge to, so the mod
	 * degrades to a runtime that accepts and discards rather than failing to
	 * start: the tab list then shows whatever vanilla would.
	 *
	 * @param dependencyManager where the bus, the placeholder runtime, permissions
	 *                          and the player state source come from
	 */
	@SuppressWarnings("unchecked")
	public static TabBridgeRuntime create(LunaLogger logger, MinecraftServer server, DependencyManager dependencyManager) {
		LunaLogger runtimeLogger = logger.scope("Runtime");
		PluginMessageBus<ServerPlayer, ServerPlayer> bus = (PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager
			.resolveOptional(PluginMessageBus.class)
			.orElse(null);

		if (bus == null) {
			runtimeLogger.warn("Thiếu PluginMessageBus, fallback sang TAB bridge runtime no-op.");
			return new NoopTabBridgeRuntime();
		}

		PlaceholderRuntime<ServerPlayer> placeholders = (PlaceholderRuntime<ServerPlayer>) dependencyManager
			.resolveOptional(PlaceholderRuntime.class)
			.orElse(null);

		if (placeholders == null) {
			runtimeLogger.warn("Thiếu PlaceholderRuntime từ LunaCore; tab list sẽ không có placeholder động.");
		}

		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		TabBridgePlayerStateSource playerStateSource = dependencyManager.resolveOptional(TabBridgePlayerStateSource.class)
			.orElseGet(NoopTabBridgePlayerStateSource::new);
		TabBridgeRelationalPlaceholderSource relationalSource = dependencyManager.resolveOptional(TabBridgeRelationalPlaceholderSource.class)
			.orElseGet(() -> new BuiltInTabBridgeRelationalPlaceholderSource(server, placeholders, permissionService));

		return new RawChannelTabBridgeRuntime(runtimeLogger, bus, permissionService, playerStateSource, placeholders, relationalSource);
	}
}
