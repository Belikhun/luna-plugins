package dev.belikhun.luna.core.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.command.LunaCommand;
import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.forwarding.ForwardingInjector;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.core.mc12.permission.PermissionMirrorListener;
import dev.belikhun.luna.core.mc12.runtime.LegacyTickListener;
import dev.belikhun.luna.core.mc12.runtime.LegacyPlayerBridge;
import dev.belikhun.luna.core.mc12.ui.LegacyChatPrompts;
import dev.belikhun.luna.core.mc12.serverselector.ServerSelectorController;
import dev.belikhun.luna.core.mc12.serverselector.SelectorCleanupListener;
import dev.belikhun.luna.legacy.placeholder.PlaceholderService;
import dev.belikhun.luna.core.mc12.placeholder.BuiltinLegacyPlaceholders;
import dev.belikhun.luna.core.mc12.placeholder.LegacyPlaceholderService;
import dev.belikhun.luna.core.mc12.placeholder.PermissionLegacyPlaceholders;
import dev.belikhun.luna.core.mc12.placeholder.PlaceholderRefreshListener;
import dev.belikhun.luna.core.mc12.runtime.LegacyServerProbe;
import dev.belikhun.luna.legacy.config.BackendCoreConfigLoader;
import dev.belikhun.luna.legacy.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.legacy.config.ForwardingSecretResolver;
import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.database.DatabaseConnector;
import dev.belikhun.luna.legacy.database.NoopDatabase;
import dev.belikhun.luna.legacy.dependency.DependencyManager;
import dev.belikhun.luna.legacy.heartbeat.BackendIdentity;
import dev.belikhun.luna.legacy.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.legacy.heartbeat.BackendStatusStore;
import dev.belikhun.luna.legacy.heartbeat.BackendStatusView;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.permission.MirroredPermissionService;
import dev.belikhun.luna.legacy.permission.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * LunaCore on 1.12.2.
 *
 * Legacy FML, not ModLauncher: the descriptor is `mcmod.info` rather than
 * `mods.toml`, the entry point is `@Mod` with a `modid` attribute rather than a bare
 * id, and lifecycle arrives through `@Mod.EventHandler` methods rather than a
 * constructor subscribing to a bus. Everything below the bootstrap is shared in shape
 * with the other platforms and in nothing else.
 *
 * Two deliberate absences, both of which are hard requirements on every other forge
 * module:
 *
 * - **LuckPerms is not a dependency.** No build of it exists below 1.19, so the
 *   `mandatory=true` the modern descriptors carry would stop this mod loading at all.
 *   Permissions are mirrored from the proxy instead.
 * - **`acceptableRemoteVersions = "*"`.** A backend mod must not force a client to
 *   carry it. luna is server-side, and 1.12.2 FML refuses a connection on a mod-list
 *   mismatch unless told otherwise.
 */
@Mod(
	modid = LunaCoreMc12Mod.MOD_ID,
	name = "LunaCore",
	version = "0.1.0-SNAPSHOT",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaCoreMc12Mod {
	public static final String MOD_ID = "lunacore";

	/**
	 * Scoped by mod id, not a bare "config.yml". One class loader serves every mod on
	 * a forge server, so an unscoped resource resolves to whichever luna jar the
	 * loader reached first.
	 */
	private static final String CONFIG_RESOURCE = MOD_ID + "/config.yml";

	private LunaLogger logger;
	private Path configDir;
	private BackendCoreRuntimeConfig runtimeConfig;
	private YamlConfigFile config;
	private Database database = new NoopDatabase();
	private BackendHeartbeatPublisher heartbeatPublisher;
	private MirroredPermissionService permissions;
	private ServerSelectorController selector;
	private LegacyPlayerBridge playerBridge;
	private MinecraftServer server;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaCore");

		File configRoot = event.getModConfigurationDirectory();

		// the forwarding secret is read from this same directory: PCF writes
		// proxy-compatible-forge.toml beside luna's own config
		configDir = configRoot.toPath();

		runtimeConfig = BackendCoreConfigLoader.loadRuntimeConfig(
			configDir.resolve(MOD_ID).resolve("config.yml"),
			LunaCoreMc12Mod.class,
			CONFIG_RESOURCE,
			logger
		);

		if (runtimeConfig.debugLoggingEnabled()) {
			logger = ((LegacyLunaLogger) logger).withDebug(true);
		}

		// the same file again, unparsed. The loader above has already created it and
		// merged in any key the shipped defaults gained, so reading it now is what
		// gives a feature mod a section this bootstrap knows nothing about
		config = YamlConfigFile.loadOrEmpty(configDir.resolve(MOD_ID).resolve("config.yml"));

		logger.audit("LunaCore (Forge 1.12.2) đã khởi tạo cấu hình.");
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		server = event.getServer();

		LegacyServerProbe probe = new LegacyServerProbe(server, configDir);

		// listeners re-render what the player is looking at, so they run on the
		// server thread
		BackendStatusStore statusStore = new BackendStatusStore(logger, probe::execute);

		heartbeatPublisher = new BackendHeartbeatPublisher(
			probe,
			logger,
			runtimeConfig.heartbeatConfig(),
			statusStore,
			"luna-mc12-heartbeat"
		);

		heartbeatPublisher.start();

		String forwardingSecret = ForwardingSecretResolver.resolve(probe.configDir(), logger);

		// before anything else on the connection path: the listening socket is
		// already bound by now, and every login from here on has to go through it
		if (ForwardingInjector.install(event.getServer(), forwardingSecret, logger)) {
			logger.success("Modern forwarding đang hoạt động; danh tính người chơi lấy từ proxy.");
		} else {
			logger.info("Modern forwarding tắt (không có forwarding secret); người chơi vào thẳng cổng này.");
		}

		permissions = createPermissionMirror(probe, forwardingSecret);

		// published before the command registers, so a feature mod whose own
		// handler runs immediately after this one already finds a full registry
		DependencyManager registry = new DependencyManager();
		registry.register(PermissionService.class, permissions);

		// Disabled in the shipped defaults, and a NoopDatabase when it stays that way
		// rather than a null: every repository above this checks for the no-op and
		// takes its "database off" path, so a backend with no database is a backend
		// that records no history, not one that throws on the first trade.
		database = DatabaseConnector.connect(config.section("database"), logger);

		registry.register(Database.class, database);
		registry.register(YamlConfigFile.class, config);

		// both faces of the same mirror: a reader asks for the view, and whoever has
		// to know *when* a backend went up or down asks for the store
		registry.register(BackendStatusView.class, statusStore);
		registry.register(BackendStatusStore.class, statusStore);

		// what a feature mod needs to reach the cluster on its own: the publisher to
		// hang a config consumer off, the identity that names this server's AMQP
		// queue, and the runtime config carrying the audit switches. The modern cores
		// publish the same three, which is what lets a feature mod be the same shape
		// on every platform.
		registry.register(BackendHeartbeatPublisher.class, heartbeatPublisher);
		registry.register(BackendIdentity.class, heartbeatPublisher.identity());
		registry.register(BackendCoreRuntimeConfig.class, runtimeConfig);

		playerBridge = new LegacyPlayerBridge(server);
		registry.register(PlayerBridge.class, playerBridge);

		// a chest has no text field, so every module that needs one borrows the
		// player's next chat line; one store, shared, or two modules would each
		// think the answer was theirs
		LegacyChatPrompts chatPrompts = new LegacyChatPrompts(server);

		registry.register(LegacyChatPrompts.class, chatPrompts);

		// the tab list and the messenger both resolve through this; the vault and any
		// other module publish their own namespaces into it as extensions
		LegacyPlaceholderService placeholders = new LegacyPlaceholderService(
			logger,
			server,
			probe,
			heartbeatPublisher.identity(),
			Arrays.<LegacyPlaceholderService.LegacyPlaceholderProvider>asList(
				new BuiltinLegacyPlaceholders(),
				new PermissionLegacyPlaceholders(permissions)
			)
		);

		registry.register(PlaceholderService.class, placeholders);
		registry.register(LegacyPlaceholderService.class, placeholders);

		LunaCore.set(registry);

		MinecraftForge.EVENT_BUS.register(chatPrompts);
		MinecraftForge.EVENT_BUS.register(new LegacyTickListener(heartbeatPublisher));
		MinecraftForge.EVENT_BUS.register(new PlaceholderRefreshListener(placeholders));
		MinecraftForge.EVENT_BUS.register(new PermissionMirrorListener(permissions));
		event.registerServerCommand(new LunaCommand(permissions, heartbeatPublisher, statusStore));

		startServerSelector(registry);

		if (permissions.isAvailable()) {
			logger.info("Permission mirror đọc từ proxy (LuckPerms không có bản Forge 1.12.2).");
		} else {
			logger.warn("Permission mirror chưa hoạt động: thiếu forwarding secret hoặc endpoint heartbeat.");
		}

		logger.success("LunaCore (Forge 1.12.2) đã khởi động thành công.");
	}

	/**
	 * The server selector: `/servers` on the proxy opens a menu *here*.
	 *
	 * The proxy pushes the selector's layout over the heartbeat and asks for the menu
	 * over the plugin-message bus, so this is registered even though nothing on this
	 * backend has a command for it. The bus may not exist yet - the messaging mod
	 * loads after the core - which is why the controller re-checks on every entry
	 * point rather than only here.
	 */
	private void startServerSelector(DependencyManager registry) {
		selector = new ServerSelectorController(
			playerBridge,
			registry,
			logger,
			permissions
		);

		heartbeatPublisher.setSelectorPayloadConsumer(payload -> selector.acceptSelectorPayload(payload));
		selector.start(heartbeatPublisher);
		registry.register(ServerSelectorController.class, selector);

		MinecraftForge.EVENT_BUS.register(new SelectorCleanupListener(selector));
	}

	/**
	 * The mirror reads the same endpoint and the same secret the heartbeat does, so a
	 * 1.12.2 backend still configures exactly one address - and the same secret again
	 * authenticates modern forwarding, which is why it is resolved once above and
	 * handed to all three rather than read from disk per consumer.
	 */
	private MirroredPermissionService createPermissionMirror(LegacyServerProbe probe, String forwardingSecret) {
		BackendCoreRuntimeConfig.HeartbeatConfig heartbeat = runtimeConfig.heartbeatConfig();

		return new MirroredPermissionService(
			logger,
			URI.create(heartbeat.endpoint()),
			forwardingSecret,
			heartbeat.serverName(),
			heartbeat.connectTimeoutMillis(),
			heartbeat.readTimeoutMillis()
		);
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		// shutdown publishes online=false, so the proxy flips this backend offline
		// immediately rather than waiting out the heartbeat timeout
		if (heartbeatPublisher != null) {
			heartbeatPublisher.shutdown();
			heartbeatPublisher = null;
		}

		// cleared first: a feature mod stopping after this must find nothing
		// rather than a service whose executor is already shut down
		LunaCore.clear();

		if (selector != null) {
			selector.close();
			selector = null;
		}

		if (permissions != null) {
			permissions.close();
			permissions = null;
		}

		// last, and replaced rather than nulled: a feature mod shutting down after
		// this may still reach for it, and a no-op answering nothing is better than
		// a null dereference on the way out
		database.close();
		database = new NoopDatabase();

		logger.audit("LunaCore (Forge 1.12.2) đã dừng.");
	}
}
