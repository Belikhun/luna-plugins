package dev.belikhun.luna.tabbridge.forge.bootstrap;

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
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

@Mod(LunaTabBridgeForgeMod.MOD_ID)
public final class LunaTabBridgeForgeMod {
	public static final String MOD_ID = "lunatabbridge";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private TabBridgeRuntime tabBridgeRuntime;
	private TabBridgeRelationalPlaceholderSource relationalPlaceholderSource;
	private TabBridgePlaceholderUpdater placeholderUpdater;

	public LunaTabBridgeForgeMod() {
		this.logger = LunaLoggers.create("LunaTabBridge", true);
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaTabBridge Forge sẽ không khởi động.");
			return;
		}

		dependencyManager = LunaCore.services().dependencyManager();
		tabBridgeRuntime = TabBridgeRuntimeFactory.create(logger, dependencyManager);
		PlaceholderService placeholderService = dependencyManager.resolveOptional(PlaceholderService.class)
			.orElseThrow(() -> new IllegalStateException("Thiếu PlaceholderService từ LunaCore Forge."));
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
		logger.success("Luna TAB Bridge Forge runtime đã sẵn sàng.");
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
