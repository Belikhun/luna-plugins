package dev.belikhun.luna.core.neoforge.bootstrap;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForgeServices;
import dev.belikhun.luna.core.neoforge.config.NeoForgeCoreConfigLoader;
import dev.belikhun.luna.core.neoforge.config.NeoForgeCoreRuntimeConfig;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeBackendStatusView;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeHeartbeatPublisher;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;
import dev.belikhun.luna.core.neoforge.placeholder.BuiltInNeoForgePlaceholderService;
import dev.belikhun.luna.core.neoforge.placeholder.NeoForgePlaceholderProviderFactory;
import dev.belikhun.luna.core.neoforge.placeholder.NeoForgePlaceholderService;
import dev.belikhun.luna.core.neoforge.serverselector.NeoForgeServerSelectorController;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(LunaCoreNeoForgeMod.MOD_ID)
public final class LunaCoreNeoForgeMod {
	public static final String MOD_ID = "lunacore";
	private static final String LUCKPERMS_MOD_ID = "luckperms";

	private LunaLogger logger;
	private final DependencyManager dependencyManager;
	private NeoForgeHeartbeatPublisher heartbeatPublisher;
	private NeoForgePlaceholderService placeholderService;
	private NeoForgeServerSelectorController serverSelectorController;

	public LunaCoreNeoForgeMod() {
		this.logger = NeoForgeLunaLoggers.create("LunaCore", true);
		this.dependencyManager = new DependencyManager();
		this.heartbeatPublisher = null;
		this.placeholderService = null;
		this.serverSelectorController = null;
		NeoForge.EVENT_BUS.register(this);
		logger.audit("Đã đăng ký LunaCore NeoForge bootstrap.");
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();
		enforceRequiredDependencies();
		NeoForgeCoreRuntimeConfig runtimeConfig = NeoForgeCoreConfigLoader.loadRuntimeConfig(getClass(), logger);
		this.logger = NeoForgeLunaLoggers.create(
			"LunaCore",
			runtimeConfig.ansiLoggingEnabled(),
			runtimeConfig.debugLoggingEnabled()
		);
		if (runtimeConfig.debugLoggingEnabled()) {
			logger.info("Đang bật debug logging cho Luna Core NeoForge (logging.level=" + runtimeConfig.loggingLevel() + ").");
		}

		AmqpMessagingConfig amqpMessagingConfig = runtimeConfig.amqpMessagingConfig();
		String localServerName = amqpMessagingConfig.effectiveLocalServerName("backend");
		if (localServerName == null || localServerName.isBlank()) {
			localServerName = "backend";
		}
		PermissionService permissionService = new LuckPermsService();
		NeoForgeBackendStatusView backendStatusView = new NeoForgeBackendStatusView();
		heartbeatPublisher = new NeoForgeHeartbeatPublisher(server, logger, runtimeConfig.heartbeatConfig(), amqpMessagingConfig, backendStatusView);
		placeholderService = new BuiltInNeoForgePlaceholderService(
			logger,
			server,
			localServerName,
			NeoForgePlaceholderProviderFactory.createDefault(permissionService),
			heartbeatPublisher::currentBackendMetadata
		);
		serverSelectorController = new NeoForgeServerSelectorController(server, dependencyManager, logger, permissionService);
		dependencyManager.registerSingleton(MinecraftServer.class, server);
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(NeoForgeCoreRuntimeConfig.class, runtimeConfig);
		dependencyManager.registerSingleton(AmqpMessagingConfig.class, amqpMessagingConfig);
		dependencyManager.registerSingleton(PermissionService.class, permissionService);
		dependencyManager.registerSingleton(BackendStatusView.class, backendStatusView);
		dependencyManager.registerSingleton(NeoForgeBackendStatusView.class, backendStatusView);
		dependencyManager.registerSingleton(NeoForgeHeartbeatPublisher.class, heartbeatPublisher);
		dependencyManager.registerSingleton(NeoForgePlaceholderService.class, placeholderService);
		dependencyManager.registerSingleton(NeoForgeServerSelectorController.class, serverSelectorController);
		LunaCoreNeoForge.set(new LunaCoreNeoForgeServices(MOD_ID, server, dependencyManager, logger, heartbeatPublisher));
		heartbeatPublisher.start();
		serverSelectorController.start(heartbeatPublisher);
		logger.success("LunaCore NeoForge đã khởi động bootstrap với LuckPerms permission service và heartbeat publisher.");
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		if (serverSelectorController != null) {
			serverSelectorController.registerCommands(event);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (heartbeatPublisher == null) {
			return;
		}

		heartbeatPublisher.publishNow();
		if (serverSelectorController != null) {
			serverSelectorController.ensureMessagingAttached();
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (serverSelectorController != null && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
			serverSelectorController.cleanupPlayer(player.getUUID());
		}

		if (heartbeatPublisher == null) {
			return;
		}

		heartbeatPublisher.publishNow();
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (serverSelectorController != null) {
			serverSelectorController.close();
			serverSelectorController = null;
		}

		if (heartbeatPublisher != null) {
			heartbeatPublisher.shutdown();
			heartbeatPublisher = null;
		}

		placeholderService = null;

		dependencyManager.clear();
		LunaCoreNeoForge.clear();
		logger.audit("LunaCore NeoForge đã dọn dẹp bootstrap.");
	}

	private void enforceRequiredDependencies() {
		if (!ModList.get().isLoaded(LUCKPERMS_MOD_ID)) {
			throw new IllegalStateException("LunaCore NeoForge yêu cầu LuckPerms trên NeoForge backend. Hãy cài mod 'luckperms' trước khi khởi động Luna modules.");
		}
	}
}
