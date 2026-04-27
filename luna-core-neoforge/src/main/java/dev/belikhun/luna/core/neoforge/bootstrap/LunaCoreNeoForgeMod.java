package dev.belikhun.luna.core.neoforge.bootstrap;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForgeServices;
import dev.belikhun.luna.core.neoforge.config.NeoForgeCoreConfigLoader;
import dev.belikhun.luna.core.neoforge.config.NeoForgeCoreRuntimeConfig;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeHeartbeatPublisher;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;
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
	private NeoForgeServerSelectorController serverSelectorController;

	public LunaCoreNeoForgeMod() {
		this.logger = NeoForgeLunaLoggers.create("LunaCoreNeoForge", true).scope("CoreNeoForge");
		this.dependencyManager = new DependencyManager();
		this.heartbeatPublisher = null;
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
			"LunaCoreNeoForge",
			runtimeConfig.ansiLoggingEnabled(),
			runtimeConfig.debugLoggingEnabled()
		).scope("CoreNeoForge");
		if (runtimeConfig.debugLoggingEnabled()) {
			logger.info("Đang bật debug logging cho Luna Core NeoForge (logging.level=" + runtimeConfig.loggingLevel() + ").");
		}

		AmqpMessagingConfig amqpMessagingConfig = runtimeConfig.amqpMessagingConfig();
		PermissionService permissionService = new LuckPermsService();
		heartbeatPublisher = new NeoForgeHeartbeatPublisher(server, logger, runtimeConfig.heartbeatConfig(), amqpMessagingConfig);
		serverSelectorController = new NeoForgeServerSelectorController(server, dependencyManager, logger, permissionService);
		dependencyManager.registerSingleton(MinecraftServer.class, server);
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(NeoForgeCoreRuntimeConfig.class, runtimeConfig);
		dependencyManager.registerSingleton(AmqpMessagingConfig.class, amqpMessagingConfig);
		dependencyManager.registerSingleton(PermissionService.class, permissionService);
		dependencyManager.registerSingleton(NeoForgeHeartbeatPublisher.class, heartbeatPublisher);
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
