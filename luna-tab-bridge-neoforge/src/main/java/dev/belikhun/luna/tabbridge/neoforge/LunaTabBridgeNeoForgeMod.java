package dev.belikhun.luna.tabbridge.neoforge;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.logging.Logger;

@Mod(LunaTabBridgeNeoForgeMod.MOD_ID)
public final class LunaTabBridgeNeoForgeMod {
	public static final String MOD_ID = "lunatabbridge";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private NeoForgeTabBridgeRuntime tabBridgeRuntime;
	private NeoForgeTabBridgePlaceholderUpdater placeholderUpdater;

	public LunaTabBridgeNeoForgeMod() {
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaTabBridgeNeoForge"), true).scope("TabBridgeNeoForge");
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCoreNeoForge.services().dependencyManager();
		tabBridgeRuntime = NeoForgeTabBridgeRuntimeFactory.create(logger, dependencyManager);
		dependencyManager.registerSingleton(NeoForgeTabBridgeRuntime.class, tabBridgeRuntime);
		String localServerName = dependencyManager.resolveOptional(AmqpMessagingConfig.class)
			.map(config -> config.effectiveLocalServerName("backend"))
			.filter(value -> value != null && !value.isBlank())
			.orElse("backend");
		placeholderUpdater = new NeoForgeTabBridgePlaceholderUpdater(
			logger,
			event.getServer(),
			tabBridgeRuntime,
			localServerName
		);
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
		if (dependencyManager != null) {
			dependencyManager.unregister(NeoForgeTabBridgeRuntime.class);
		}

		if (tabBridgeRuntime != null) {
			tabBridgeRuntime.close();
			tabBridgeRuntime = null;
		}

		if (placeholderUpdater != null) {
			placeholderUpdater.close();
			placeholderUpdater = null;
		}

		dependencyManager = null;
	}
}
