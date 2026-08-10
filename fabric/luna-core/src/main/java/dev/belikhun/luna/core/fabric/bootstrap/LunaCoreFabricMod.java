package dev.belikhun.luna.core.fabric.bootstrap;

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
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.LunaCoreFabricServices;
import dev.belikhun.luna.core.fabric.compat.GameVersion;
import dev.belikhun.luna.core.fabric.heartbeat.FabricServerProbe;
import dev.belikhun.luna.core.fabric.heartbeat.TickRateMonitor;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.core.fabric.placeholder.BuiltInFabricPlaceholderService;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import dev.belikhun.luna.core.fabric.serverselector.FabricServerSelectorController;
import dev.belikhun.luna.core.fabric.ui.FabricChatPrompts;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Fabric runtime foundation for the luna backend modules.
 *
 * The mod publishes this backend's heartbeat to the velocity proxy and serves
 * the network's server list; everything above that (economy, messenger, packs)
 * resolves its services through {@link LunaCoreFabric}, exactly as the NeoForge
 * and Paper cores are used.
 *
 * One build covers Minecraft 1.20 upward. That is possible because the mod
 * subclasses nothing in the game, registers no mixins, and reaches only stable
 * API - see {@code compat/GameVersion} for the version test and
 * {@code compat/Guarded} for how a call the game later drops is contained.
 */
public final class LunaCoreFabricMod implements DedicatedServerModInitializer {
	/** This mod's own default config inside its jar; see the note on the name. */
	private static final String CONFIG_RESOURCE = "lunacore/config.yml";

	public static final String MOD_ID = "lunacore";
	private static final String LUCKPERMS_MOD_ID = "luckperms";

	/** Half a second at a healthy tick rate; slower when the server is struggling. */
	private static final int PLACEHOLDER_REFRESH_TICKS = 10;

	private LunaLogger logger;
	private final DependencyManager dependencyManager;
	private final TickRateMonitor tickRate;
	private BackendHeartbeatPublisher heartbeatPublisher;
	private FabricServerSelectorController serverSelectorController;
	private PlaceholderService placeholderService;
	private ChatPrompts chatPrompts;
	private Database database;
	private int ticksSincePlaceholderRefresh;

	public LunaCoreFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaCore", true);
		this.dependencyManager = new DependencyManager();
		this.tickRate = new TickRateMonitor();
		this.heartbeatPublisher = null;
		this.serverSelectorController = null;
		this.placeholderService = null;
		this.chatPrompts = null;
		this.database = new NoopDatabase();
		this.ticksSincePlaceholderRefresh = 0;
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickRate.onTick();
			refreshPlaceholders();
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			FabricServerSelectorController.registerCommands(dispatcher, () -> serverSelectorController));

		// join/leave only sharpen the reporting: a beat that misses them is late by
		// one interval rather than wrong
		registerConnectionEvents();

		// fabric's events are static, so the chat hook is installed once here and the
		// service behind it is swapped per server start
		FabricChatPrompts.registerEvents(() -> chatPrompts);

		logger.audit("Đã đăng ký LunaCore Fabric bootstrap trên Minecraft " + GameVersion.display() + ".");
	}

	private void registerConnectionEvents() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (heartbeatPublisher != null) {
				heartbeatPublisher.publishNow();
			}

			if (serverSelectorController != null) {
				serverSelectorController.ensureMessagingAttached();
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (serverSelectorController != null && handler.getPlayer() != null) {
				serverSelectorController.cleanupPlayer(handler.getPlayer().getUUID());
			}

			if (heartbeatPublisher != null) {
				heartbeatPublisher.publishNow();
			}
		});
	}

	private void onServerStarted(MinecraftServer server) {
		Path configPath = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize()
			.resolve(MOD_ID).resolve("config.yml");
		BackendCoreRuntimeConfig runtimeConfig = BackendCoreConfigLoader.loadRuntimeConfig(configPath, getClass(), CONFIG_RESOURCE, logger);

		// the same file again, this time as the whole tree: the runtime config is
		// the slice every platform parses identically, while the modules above the
		// core read their own keys out of it the way they do from Paper's ConfigStore
		YamlConfigFile config = YamlConfigFile.load(configPath, getClass(), CONFIG_RESOURCE);

		this.logger = FabricLunaLoggers.create(
			"LunaCore",
			runtimeConfig.ansiLoggingEnabled(),
			runtimeConfig.debugLoggingEnabled()
		);
		if (runtimeConfig.debugLoggingEnabled()) {
			logger.info("Đang bật debug logging cho Luna Core Fabric (logging.level=" + runtimeConfig.loggingLevel() + ").");
		}

		// constructing this is what loads the LuckPerms API classes, so it cannot
		// happen before the check: without the mod installed it throws
		// NoClassDefFoundError and takes the whole bootstrap - heartbeat included -
		// down with it
		PermissionService permissionService = permissionsAvailable() ? new LuckPermsService() : null;

		// listeners re-render what the player is looking at, so they run on the
		// server thread
		BackendStatusStore backendStatusStore = new BackendStatusStore(logger, server::execute);
		heartbeatPublisher = new BackendHeartbeatPublisher(
			new FabricServerProbe(server, tickRate),
			logger,
			runtimeConfig.heartbeatConfig(),
			backendStatusStore,
			"luna-fabric-heartbeat"
		);
		BackendIdentity backendIdentity = heartbeatPublisher.identity();

		database = DatabaseConnector.connect(config.section("database"), logger);
		chatPrompts = new ChatPrompts(server);

		dependencyManager.registerSingleton(MinecraftServer.class, server);
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(BackendCoreRuntimeConfig.class, runtimeConfig);
		dependencyManager.registerSingleton(YamlConfigFile.class, config);
		dependencyManager.registerSingleton(Database.class, database);
		dependencyManager.registerSingleton(ChatPrompts.class, chatPrompts);
		dependencyManager.registerSingleton(BackendStatusView.class, backendStatusStore);
		dependencyManager.registerSingleton(BackendStatusStore.class, backendStatusStore);
		dependencyManager.registerSingleton(BackendIdentity.class, backendIdentity);
		dependencyManager.registerSingleton(BackendHeartbeatPublisher.class, heartbeatPublisher);

		heartbeatPublisher.start();

		if (permissionService != null) {
			dependencyManager.registerSingleton(PermissionService.class, permissionService);
			serverSelectorController = new FabricServerSelectorController(server, dependencyManager, logger, permissionService);
			dependencyManager.registerSingleton(FabricServerSelectorController.class, serverSelectorController);
			serverSelectorController.start(heartbeatPublisher);
		}

		// the statistics behind a placeholder are sampled on the heartbeat's own
		// cadence rather than per lookup: a tab list asking sixty players for the
		// same TPS must not count the world's entities sixty times
		placeholderService = BuiltInFabricPlaceholderService.createDefault(
			logger,
			server,
			tickRate,
			backendIdentity,
			permissionService
		);
		dependencyManager.registerSingleton(PlaceholderService.class, placeholderService);
		placeholderService.refreshSharedSnapshot();

		LunaCoreFabric.set(new LunaCoreFabricServices(MOD_ID, server, dependencyManager, logger, heartbeatPublisher, config, database));
		logger.success("LunaCore Fabric đã khởi động bootstrap với heartbeat publisher.");
	}

	private void onServerStopping(MinecraftServer server) {
		placeholderService = null;
		chatPrompts = null;

		if (serverSelectorController != null) {
			serverSelectorController.close();
			serverSelectorController = null;
		}

		if (heartbeatPublisher != null) {
			heartbeatPublisher.shutdown();
			heartbeatPublisher = null;
		}

		database.close();
		database = new NoopDatabase();

		dependencyManager.clear();
		LunaCoreFabric.clear();
		logger.audit("LunaCore Fabric đã dọn dẹp bootstrap.");
	}

	/**
	 * Re-sample the statistics behind the placeholders, twice a second.
	 *
	 * The core drives this rather than whoever reads the values: several modules
	 * resolve placeholders, and each one refreshing on its own cadence would count
	 * the world's entities once per module instead of once per interval.
	 */
	private void refreshPlaceholders() {
		if (placeholderService == null) {
			return;
		}

		ticksSincePlaceholderRefresh++;

		if (ticksSincePlaceholderRefresh < PLACEHOLDER_REFRESH_TICKS) {
			return;
		}

		ticksSincePlaceholderRefresh = 0;
		placeholderService.refreshSharedSnapshot();
	}

	/**
	 * The selector gates servers on permissions, so without LuckPerms it would
	 * show every player every backend. NeoForge refuses to start in that case;
	 * here the heartbeat still runs, because a backend the console cannot see is
	 * a worse failure than a server list nobody can open.
	 */
	private boolean permissionsAvailable() {
		if (FabricLoader.getInstance().isModLoaded(LUCKPERMS_MOD_ID)) {
			return true;
		}

		logger.error("Không tìm thấy mod 'luckperms'. Server selector sẽ tắt; heartbeat vẫn chạy bình thường.");
		return false;
	}
}
