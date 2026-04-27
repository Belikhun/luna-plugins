package dev.belikhun.luna.tabbridge.neoforge;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.NeoForgeHeartbeatPublisher;
import dev.belikhun.luna.core.neoforge.NeoForgeLunaLoggers;
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
	private NeoForgeTabBridgeRuntime tabBridgeRuntime;
	private NeoForgeTabBridgeRelationalPlaceholderSource relationalPlaceholderSource;
	private NeoForgeTabBridgePlaceholderUpdater placeholderUpdater;

	public LunaTabBridgeNeoForgeMod() {
		this.logger = NeoForgeLunaLoggers.create("LunaTabBridgeNeoForge", true).scope("TabBridgeNeoForge");
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCoreNeoForge.services().dependencyManager();
		tabBridgeRuntime = NeoForgeTabBridgeRuntimeFactory.create(logger, dependencyManager);
		PermissionService permissionService = dependencyManager.resolveOptional(PermissionService.class).orElse(null);
		relationalPlaceholderSource = dependencyManager.resolveOptional(NeoForgeTabBridgeRelationalPlaceholderSource.class)
			.orElseGet(() -> new BuiltInNeoForgeTabBridgeRelationalPlaceholderSource(event.getServer(), permissionService));
		dependencyManager.registerSingleton(NeoForgeTabBridgeRuntime.class, tabBridgeRuntime);
		String localServerName = dependencyManager.resolveOptional(AmqpMessagingConfig.class)
			.map(config -> config.effectiveLocalServerName("backend"))
			.filter(value -> value != null && !value.isBlank())
			.orElse("backend");
		java.util.function.Supplier<BackendMetadata> currentBackendMetadataSupplier = dependencyManager.resolveOptional(NeoForgeHeartbeatPublisher.class)
			.<java.util.function.Supplier<BackendMetadata>>map(publisher -> publisher::currentBackendMetadata)
			.orElse(() -> null);
		placeholderUpdater = new NeoForgeTabBridgePlaceholderUpdater(
			logger,
			event.getServer(),
			tabBridgeRuntime,
			relationalPlaceholderSource,
			localServerName,
			currentBackendMetadataSupplier
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
			dependencyManager.unregister(NeoForgeTabBridgeRuntime.class);
		}

		if (tabBridgeRuntime != null) {
			tabBridgeRuntime.close();
			tabBridgeRuntime = null;
		}

		relationalPlaceholderSource = null;
		dependencyManager = null;
	}
}
