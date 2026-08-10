package dev.belikhun.luna.tabbridge.neoforge.bootstrap;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import dev.belikhun.luna.tabbridge.mc.runtime.BuiltInTabBridgeRelationalPlaceholderSource;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgePlaceholderUpdater;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgeRelationalPlaceholderSource;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgeRuntime;
import dev.belikhun.luna.tabbridge.mc.runtime.TabBridgeRuntimeFactory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(LunaTabBridgeNeoForgeMod.MOD_ID)
public final class LunaTabBridgeNeoForgeMod {
	public static final String MOD_ID = "lunatabbridge";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private TabBridgeRuntime tabBridgeRuntime;
	private TabBridgeRelationalPlaceholderSource relationalPlaceholderSource;
	private TabBridgePlaceholderUpdater placeholderUpdater;

	public LunaTabBridgeNeoForgeMod() {
		this.logger = LunaLoggers.create("LunaTabBridge", true);
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCore.services().dependencyManager();
		tabBridgeRuntime = TabBridgeRuntimeFactory.create(logger, dependencyManager);
		PlaceholderService placeholderService = dependencyManager.resolveOptional(PlaceholderService.class)
			.orElseThrow(() -> new IllegalStateException("Thiếu PlaceholderService từ LunaCore NeoForge."));
		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		relationalPlaceholderSource = dependencyManager.resolveOptional(TabBridgeRelationalPlaceholderSource.class)
			.orElseGet(() -> new BuiltInTabBridgeRelationalPlaceholderSource(event.getServer(), permissionService));
		dependencyManager.registerSingleton(TabBridgeRuntime.class, tabBridgeRuntime);
		placeholderUpdater = new TabBridgePlaceholderUpdater(
			event.getServer(),
			tabBridgeRuntime,
			relationalPlaceholderSource,
			placeholderService
		);
		tabBridgeRuntime.bindPlaceholderResolver(placeholderUpdater::resolvePlaceholder);
		placeholderUpdater.refreshOnlinePlayers();
		logger.success("Luna TAB Bridge NeoForge runtime đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (placeholderUpdater == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			return;
		}

		placeholderUpdater.refreshPlayer(player);
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (tabBridgeRuntime == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			return;
		}

		tabBridgeRuntime.removePlayer(player.getUUID());
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (placeholderUpdater != null) {
			placeholderUpdater.close();
			placeholderUpdater = null;
		}

		if (dependencyManager != null) {
			dependencyManager.unregister(TabBridgeRuntime.class);
		}

		if (tabBridgeRuntime != null) {
			tabBridgeRuntime.close();
			tabBridgeRuntime = null;
		}

		relationalPlaceholderSource = null;
		dependencyManager = null;
	}
}
