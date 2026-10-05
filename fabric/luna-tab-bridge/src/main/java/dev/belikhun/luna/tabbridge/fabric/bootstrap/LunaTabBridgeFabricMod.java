package dev.belikhun.luna.tabbridge.fabric.bootstrap;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.PlaceholderTransportInspector;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgePlaceholderUpdater;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgeRuntime;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgeRuntimeFactory;
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
 * over the plugin-message channel TAB itself defines. The runtime is the same
 * class the NeoForge and Forge bridges run; only this lifecycle glue is Fabric's.
 */
public final class LunaTabBridgeFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunatabbridge";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private TabBridgeRuntime tabBridgeRuntime;
	private TabBridgePlaceholderUpdater placeholderUpdater;

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
		tabBridgeRuntime = TabBridgeRuntimeFactory.create(logger, server, dependencyManager);
		dependencyManager.registerSingleton(TabBridgeRuntime.class, tabBridgeRuntime);
		dependencyManager.registerSingleton(PlaceholderTransportInspector.class, tabBridgeRuntime);
		placeholderUpdater = new TabBridgePlaceholderUpdater(server, tabBridgeRuntime);
		placeholderUpdater.refreshOnlinePlayers();
		logger.success("Luna TAB Bridge Fabric runtime đã sẵn sàng.");
	}

	private void onServerStopping(MinecraftServer server) {
		if (placeholderUpdater != null) {
			placeholderUpdater.close();
			placeholderUpdater = null;
		}

		if (dependencyManager != null) {
			dependencyManager.unregister(TabBridgeRuntime.class);
			dependencyManager.unregister(PlaceholderTransportInspector.class);
			dependencyManager = null;
		}

		if (tabBridgeRuntime != null) {
			tabBridgeRuntime.close();
			tabBridgeRuntime = null;
		}
	}
}
