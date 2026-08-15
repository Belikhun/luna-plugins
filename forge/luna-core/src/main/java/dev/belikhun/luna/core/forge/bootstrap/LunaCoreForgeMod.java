package dev.belikhun.luna.core.forge.bootstrap;

import dev.belikhun.luna.core.api.config.BackendCoreConfigLoader;
import dev.belikhun.luna.core.api.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabaseConnector;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusStore;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.LunaCoreServices;
import dev.belikhun.luna.core.mc.heartbeat.ServerProbe;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.core.mc.placeholder.BuiltInPlaceholderService;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderProviderFactory;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import dev.belikhun.luna.core.mc.serverselector.ServerSelectorController;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import net.minecraftforge.event.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * LunaCore on classic forge.
 *
 * The loader-facing half of the module and the only part that is forge's own.
 * Everything it drives - the placeholder service, the heartbeat, the server
 * selector, the chat prompts - is the same code the neoforge build runs, shared
 * by source from core/luna-core-mc; see that module's README.
 *
 * Forge's bus is the static {@code MinecraftForge.EVENT_BUS} rather than an
 * injected one, and its events sit under {@code net.minecraftforge}. The names
 * are otherwise the pre-fork ones, which is why this reads so closely to the
 * neoforge bootstrap: neoforge forked from this loader at 1.20.1.
 */
@Mod(LunaCoreForgeMod.MOD_ID)
public final class LunaCoreForgeMod {
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

	public LunaCoreForgeMod() {
		this.logger = LunaLoggers.create("LunaCore", true);
		this.dependencyManager = new DependencyManager();
		this.heartbeatPublisher = null;
		this.placeholderService = null;
		this.serverSelectorController = null;

		MinecraftForge.EVENT_BUS.register(this);
		logger.audit("Đã đăng ký LunaCore Forge bootstrap.");
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();

		enforceRequiredDependencies();

		BackendCoreRuntimeConfig runtimeConfig = BackendCoreConfigLoader.loadRuntimeConfig(CONFIG_PATH, getClass(), CONFIG_RESOURCE, logger);

		// the same file again, this time as the whole tree: the runtime config is
		// the slice every platform parses identically, while the modules above the
		// core read their own keys out of it
		YamlConfigFile config = YamlConfigFile.load(CONFIG_PATH, getClass(), CONFIG_RESOURCE);

		this.logger = LunaLoggers.create(
			"LunaCore",
			runtimeConfig.ansiLoggingEnabled(),
			runtimeConfig.debugLoggingEnabled()
		);

		if (runtimeConfig.debugLoggingEnabled()) {
			logger.info("Đang bật debug logging cho Luna Core Forge (logging.level=" + runtimeConfig.loggingLevel() + ").");
		}

		PermissionService permissionService = new LuckPermsService();
		// listeners re-render open menus, so they run on the server thread
		BackendStatusStore backendStatusStore = new BackendStatusStore(logger, server::execute);

		heartbeatPublisher = new BackendHeartbeatPublisher(
			new ServerProbe(server, FMLPaths.CONFIGDIR.get()),
			logger,
			runtimeConfig.heartbeatConfig(),
			backendStatusStore,
			"luna-forge-heartbeat"
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
		logger.success("LunaCore Forge đã khởi động bootstrap với LuckPerms permission service và heartbeat publisher.");
	}

	// paired, so the heartbeat reports what a tick cost rather than how far apart
	// two of them were; a healthy server sleeps most of the gap
	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (heartbeatPublisher == null) {
			return;
		}

		if (event.phase == TickEvent.Phase.START) {
			heartbeatPublisher.tickStarted();
		} else {
			heartbeatPublisher.tickEnded();
		}
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
		if (chatPrompts != null && event.getEntity() instanceof ServerPlayer leaving) {
			chatPrompts.cancel(leaving.getUUID());
		}
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (heartbeatPublisher != null) {
			heartbeatPublisher.stop();
		}

		if (database != null) {
			database.close();
		}

		logger.info("LunaCore Forge đã dừng.");
	}

	/**
	 * LuckPerms is what every permission check in luna goes through, so a server
	 * without it would fail one call at a time instead of once, here.
	 */
	private void enforceRequiredDependencies() {
		if (!ModList.get().isLoaded(LUCKPERMS_MOD_ID)) {
			throw new IllegalStateException("LunaCore cần mod LuckPerms nhưng không tìm thấy nó trên máy chủ này.");
		}
	}
}
