package dev.belikhun.luna.tabbridge.fabric.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.level.ServerPlayer;

public final class TabBridgeRuntimeFactory {
	private TabBridgeRuntimeFactory() {
	}

	/**
	 * Build the bridge for this server.
	 *
	 * Without a plugin-message bus there is no proxy to bridge to, so the mod
	 * degrades to a runtime that accepts and discards rather than failing to
	 * start: the tab list then shows whatever vanilla would, which is a working
	 * server with a plain player list.
	 */
	@SuppressWarnings("unchecked")
	public static FabricTabBridgeRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		PluginMessageBus<ServerPlayer, ServerPlayer> bus =
			(PluginMessageBus<ServerPlayer, ServerPlayer>) dependencyManager.resolveOptional(PluginMessageBus.class).orElse(null);

		if (bus == null) {
			logger.warn("Thiếu PluginMessageBus, TAB bridge sẽ chạy ở chế độ no-op.");
			return new NoopFabricTabBridgeRuntime();
		}

		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		FabricTabBridgePlayerStateSource playerStateSource = dependencyManager.resolveOptional(FabricTabBridgePlayerStateSource.class)
			.orElseGet(NoopFabricTabBridgePlayerStateSource::new);

		return new RawChannelFabricTabBridgeRuntime(logger, bus, permissionService, playerStateSource);
	}
}
