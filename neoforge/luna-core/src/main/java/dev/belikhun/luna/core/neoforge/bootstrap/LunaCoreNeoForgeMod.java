package dev.belikhun.luna.core.neoforge.bootstrap;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.LunaCoreServices;
import dev.belikhun.luna.core.api.config.BackendCoreConfigLoader;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabaseConnector;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusStore;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.mc.heartbeat.ServerProbe;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.core.mc.placeholder.BuiltInPlaceholderService;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderProviderFactory;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import dev.belikhun.luna.core.mc.serverselector.ServerSelectorController;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;

@Mod(LunaCoreNeoForgeMod.MOD_ID)
public final class LunaCoreNeoForgeMod {
	/** This mod's own default config inside its jar; see the note on the name. */
	private static final String CONFIG_RESOURCE = "lunacore/config.yml";

	public static final String MOD_ID = "lunacore";
	private static final String LUCKPERMS_MOD_ID = "luckperms";
	private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve(MOD_ID).resolve("config.yml");

	private LunaLogger logger;
	private final DependencyManager dependencyManager;
	private BackendHeartbeatPublisher heartbeatPublisher;
	private PlaceholderService placeholderService;
	private ServerSelectorController serverSelectorController;
	private Database database = new NoopDatabase();
	private ChatPrompts chatPrompts;

	public LunaCoreNeoForgeMod() {
		this.logger = LunaLoggers.create("LunaCore", true);
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
		BackendCoreRuntimeConfig runtimeConfig = BackendCoreConfigLoader.loadRuntimeConfig(CONFIG_PATH, getClass(), CONFIG_RESOURCE, logger);

		// the same file again, this time as the whole tree: the runtime config is
		// the slice every platform parses identically, while the modules above the
		// core read their own keys out of it the way they do from Paper's ConfigStore
		YamlConfigFile config = YamlConfigFile.load(CONFIG_PATH, getClass(), CONFIG_RESOURCE);
		this.logger = LunaLoggers.create(
			"LunaCore",
			runtimeConfig.ansiLoggingEnabled(),
			runtimeConfig.debugLoggingEnabled()
		);
		if (runtimeConfig.debugLoggingEnabled()) {
			logger.info("Đang bật debug logging cho Luna Core NeoForge (logging.level=" + runtimeConfig.loggingLevel() + ").");
		}

		PermissionService permissionService = new LuckPermsService();
		// listeners re-render open menus, so they run on the server thread
		BackendStatusStore backendStatusStore = new BackendStatusStore(logger, server::execute);
		heartbeatPublisher = new BackendHeartbeatPublisher(
			new ServerProbe(server, FMLPaths.CONFIGDIR.get()),
			logger,
			runtimeConfig.heartbeatConfig(),
			backendStatusStore,
			"luna-neoforge-heartbeat"
		);
		BackendIdentity backendIdentity = heartbeatPublisher.identity();
		placeholderService = new BuiltInPlaceholderService(
			logger,
			server,
			backendIdentity,
			PlaceholderProviderFactory.createDefault(permissionService)
		);
		serverSelectorController = new ServerSelectorController(server, dependencyManager, logger, permissionService);
		database = DatabaseConnector.connect(config.section("database"), logger);
		chatPrompts = new ChatPrompts(server);

		dependencyManager.registerSingleton(MinecraftServer.class, server);
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(BackendCoreRuntimeConfig.class, runtimeConfig);
		dependencyManager.registerSingleton(YamlConfigFile.class, config);
		dependencyManager.registerSingleton(Database.class, database);
		dependencyManager.registerSingleton(ChatPrompts.class, chatPrompts);
		dependencyManager.registerSingleton(PermissionService.class, permissionService);
		dependencyManager.registerSingleton(BackendStatusView.class, backendStatusStore);
		dependencyManager.registerSingleton(BackendStatusStore.class, backendStatusStore);
		dependencyManager.registerSingleton(BackendIdentity.class, backendIdentity);
		dependencyManager.registerSingleton(BackendHeartbeatPublisher.class, heartbeatPublisher);
		dependencyManager.registerSingleton(PlaceholderService.class, placeholderService);
		dependencyManager.registerSingleton(ServerSelectorController.class, serverSelectorController);
		LunaCore.set(new LunaCoreServices(MOD_ID, server, dependencyManager, logger, heartbeatPublisher, config, database));
		heartbeatPublisher.start();
		serverSelectorController.start(heartbeatPublisher);
		logger.success("LunaCore NeoForge đã khởi động bootstrap với LuckPerms permission service và heartbeat publisher.");
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		if (serverSelectorController != null) {
			serverSelectorController.registerCommands(event.getDispatcher());
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

	/**
	 * A screen waiting on a typed answer gets the line before anyone else.
	 *
	 * HIGHEST is load-bearing: LunaMessenger cancels every chat message to route
	 * it through the proxy, so a prompt listening at the default priority would
	 * never see the answer it asked for.
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onServerChat(ServerChatEvent event) {
		if (chatPrompts == null || event.getPlayer() == null) {
			return;
		}

		if (chatPrompts.consume(event.getPlayer().getUUID(), event.getRawText())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (chatPrompts != null && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer leaving) {
			chatPrompts.cancel(leaving.getUUID());
		}

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

		database.close();
		database = new NoopDatabase();
		chatPrompts = null;

		dependencyManager.clear();
		LunaCore.clear();
		logger.audit("LunaCore NeoForge đã dọn dẹp bootstrap.");
	}

	private void enforceRequiredDependencies() {
		if (!ModList.get().isLoaded(LUCKPERMS_MOD_ID)) {
			throw new IllegalStateException("LunaCore NeoForge yêu cầu LuckPerms trên NeoForge backend. Hãy cài mod 'luckperms' trước khi khởi động Luna modules.");
		}
	}
}
