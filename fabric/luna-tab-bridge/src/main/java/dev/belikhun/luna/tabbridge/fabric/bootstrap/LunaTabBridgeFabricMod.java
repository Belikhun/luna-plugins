package dev.belikhun.luna.tabbridge.fabric.bootstrap;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.core.fabric.placeholder.FabricPlaceholderService;
import dev.belikhun.luna.tabbridge.fabric.runtime.BuiltInFabricTabBridgeRelationalPlaceholderSource;
import dev.belikhun.luna.tabbridge.fabric.runtime.FabricTabBridgePlaceholderUpdater;
import dev.belikhun.luna.tabbridge.fabric.runtime.FabricTabBridgeRelationalPlaceholderSource;
import dev.belikhun.luna.tabbridge.fabric.runtime.FabricTabBridgeRuntime;
import dev.belikhun.luna.tabbridge.fabric.runtime.TabBridgeRuntimeFactory;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The bridge between this backend and the TAB mod running on the proxy.
 *
 * The proxy renders the tab list; this side answers its questions - what world a
 * player is in, what group they hold, what a placeholder currently resolves to -
 * over the plugin-message channel TAB itself defines.
 *
 * Unlike the NeoForge build this does not refuse to start without a placeholder
 * service: the bridge's own state (world, game mode, permissions) is useful on
 * its own, and a server that will not boot is a worse answer than a tab list
 * missing its dynamic columns.
 */
public final class LunaTabBridgeFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunatabbridge";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private FabricTabBridgeRuntime tabBridgeRuntime;
	private FabricTabBridgePlaceholderUpdater placeholderUpdater;

	public LunaTabBridgeFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaTabBridge", true);
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (placeholderUpdater != null) {
				placeholderUpdater.refreshPlayer(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();

			if (tabBridgeRuntime != null && player != null) {
				tabBridgeRuntime.removePlayer(player.getUUID());
			}
		});
	}

	private void onServerStarted(MinecraftServer server) {
		dependencyManager = LunaCoreFabric.services().dependencyManager();
		tabBridgeRuntime = TabBridgeRuntimeFactory.create(logger, dependencyManager);
		dependencyManager.registerSingleton(FabricTabBridgeRuntime.class, tabBridgeRuntime);

		FabricPlaceholderService placeholderService = dependencyManager.resolveOptional(FabricPlaceholderService.class).orElse(null);

		if (placeholderService == null) {
			logger.warn("Thiếu FabricPlaceholderService từ LunaCore. Tab list sẽ không có placeholder động.");
			logger.success("Luna TAB Bridge Fabric runtime đã sẵn sàng (không có placeholder).");
			return;
		}

		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		FabricTabBridgeRelationalPlaceholderSource relationalPlaceholderSource =
			dependencyManager.resolveOptional(FabricTabBridgeRelationalPlaceholderSource.class)
				.orElseGet(() -> new BuiltInFabricTabBridgeRelationalPlaceholderSource(server, permissionService));

		placeholderUpdater = new FabricTabBridgePlaceholderUpdater(
			server,
			tabBridgeRuntime,
			relationalPlaceholderSource,
			placeholderService
		);

		tabBridgeRuntime.bindPlaceholderResolver(placeholderUpdater::resolvePlaceholder);
		placeholderUpdater.refreshOnlinePlayers();

		logger.success("Luna TAB Bridge Fabric runtime đã sẵn sàng.");
	}

	private void onServerStopping(MinecraftServer server) {
		if (placeholderUpdater != null) {
			placeholderUpdater.close();
			placeholderUpdater = null;
		}

		if (dependencyManager != null) {
			dependencyManager.unregister(FabricTabBridgeRuntime.class);
			dependencyManager = null;
		}

		if (tabBridgeRuntime != null) {
			tabBridgeRuntime.close();
			tabBridgeRuntime = null;
		}
	}
}
