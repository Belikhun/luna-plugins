package dev.belikhun.luna.pack;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.event.LunaEventManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.pack.api.LunaPackApi;
import dev.belikhun.luna.pack.command.PackAdminCommand;
import dev.belikhun.luna.pack.config.LoaderConfig;
import dev.belikhun.luna.pack.config.LoaderConfigService;
import dev.belikhun.luna.pack.listener.PlayerConnectionListener;
import dev.belikhun.luna.pack.listener.PlayerPackStatusListener;
import dev.belikhun.luna.pack.model.PackReloadReport;
import dev.belikhun.luna.pack.service.PackCatalogService;
import dev.belikhun.luna.pack.service.BuiltInPackHttpService;
import dev.belikhun.luna.pack.service.LunaPackApiService;
import dev.belikhun.luna.pack.service.PackDynamicRegistry;
import dev.belikhun.luna.pack.service.PackLoadStateBroadcastService;
import dev.belikhun.luna.pack.service.PackDispatchService;
import dev.belikhun.luna.pack.service.PlayerPackSessionStore;

import java.nio.file.Path;
import java.util.logging.Logger;

@Plugin(
	id = "lunapackloader",
	name = "LunaPackLoader",
	version = BuildConstants.VERSION,
	description = "Stacked resource-pack loader for Luna network",
	dependencies = {
		@Dependency(id = "lunacore")
	},
	authors = {"Belikhun"}
)
public final class LunaPackLoaderPlugin {
	private final ProxyServer server;
	private final LunaLogger logger;
	private final Path dataDirectory;
	private final LoaderConfigService configService;
	private final PackDynamicRegistry dynamicRegistry;
	private final PackCatalogService catalogService;
	private final BuiltInPackHttpService builtInHttpService;
	private final PlayerPackSessionStore sessionStore;
	private final PackDispatchService dispatchService;
	private final LunaEventManager packReloadEventManager;
	private final LunaPackApiService packApiService;
	private PluginMessageBus<Object, Object> pluginMessagingBus;
	private PackLoadStateBroadcastService packLoadBroadcastService;

	@Inject
	public LunaPackLoaderPlugin(ProxyServer server, @DataDirectory Path dataDirectory) {
		this.server = server;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaPackLoader"), true).scope("PackLoader");
		this.dataDirectory = dataDirectory;
		this.configService = new LoaderConfigService(dataDirectory, logger);
		this.dynamicRegistry = new PackDynamicRegistry(logger);
		this.catalogService = new PackCatalogService(dataDirectory, logger, dynamicRegistry);
		this.builtInHttpService = new BuiltInPackHttpService(server, logger);
		this.sessionStore = new PlayerPackSessionStore();
		this.dispatchService = new PackDispatchService(server, logger);
		this.packReloadEventManager = new LunaEventManager();
		this.packApiService = new LunaPackApiService(dynamicRegistry, catalogService::snapshot, this::reloadCatalog, packReloadEventManager);
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		configService.ensureDefaults();
		LunaCoreVelocity.services().dependencyManager().registerSingleton(LunaPackApi.class, packApiService);
		PackReloadReport report = reloadCatalog();
		LoaderConfig config = builtInHttpService.resolve(configService.current());
		pluginMessagingBus = LunaCoreVelocity.services().dependencyManager().resolve(VelocityPluginMessagingBus.class);
		packLoadBroadcastService = new PackLoadStateBroadcastService(server, logger, pluginMessagingBus);
		dispatchService.bindBroadcastService(packLoadBroadcastService);

		registerCommand();
		server.getEventManager().register(this, new PlayerConnectionListener(logger, sessionStore, catalogService, dispatchService, packLoadBroadcastService));
		server.getEventManager().register(this, new PlayerPackStatusListener(server, logger, sessionStore, catalogService, packLoadBroadcastService));

		logger.success("LunaPackLoader đã khởi động thành công.");
		logger.audit("base-url=" + config.baseUrl());
		logger.audit("pack-path=" + config.packPath());
		logger.audit("pack khả dụng: " + report.resolvedAvailable() + "/" + report.validDefinitions());
	}

	private synchronized PackReloadReport reloadCatalog() {
		var previousSnapshot = catalogService.snapshot();
		LoaderConfig config = builtInHttpService.resolve(configService.load());
		PackReloadReport report = catalogService.reload(config);
		packApiService.emitReload(previousSnapshot, catalogService.snapshot(), report);
		return report;
	}

	private void registerCommand() {
		CommandManager commandManager = server.getCommandManager();
		CommandMeta meta = commandManager.metaBuilder("lunapack")
			.aliases("packs")
			.plugin(this)
			.build();

		commandManager.register(meta, new PackAdminCommand(server, configService, catalogService, builtInHttpService, sessionStore, dispatchService));
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		LunaCoreVelocity.services().dependencyManager().unregister(LunaPackApi.class);
		packApiService.clearListeners();
		builtInHttpService.stopIfRunning();
	}

	public Path dataDirectory() {
		return dataDirectory;
	}
}
