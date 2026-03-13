package dev.belikhun.luna.messenger.velocity;

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
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.messenger.velocity.command.MessengerAdminCommand;
import dev.belikhun.luna.messenger.velocity.command.MessengerBroadcastCommand;
import dev.belikhun.luna.messenger.velocity.command.DiscordLinkCommand;
import dev.belikhun.luna.messenger.velocity.command.MessengerModerationCommand;
import dev.belikhun.luna.messenger.velocity.command.MessengerSpyCommand;
import dev.belikhun.luna.messenger.velocity.service.DiscordAccountLinkService;
import dev.belikhun.luna.messenger.velocity.service.DiscordBridgeGateway;
import dev.belikhun.luna.messenger.velocity.service.JdaDiscordBridgeGateway;
import dev.belikhun.luna.messenger.velocity.service.MessengerPresenceListener;
import dev.belikhun.luna.messenger.velocity.service.NoopDiscordBridgeGateway;
import dev.belikhun.luna.messenger.velocity.service.RoutingDiscordBridgeGateway;
import dev.belikhun.luna.messenger.velocity.service.SimpleTemplateRenderer;
import dev.belikhun.luna.messenger.velocity.service.VelocityMessengerConfig;
import dev.belikhun.luna.messenger.velocity.service.VelocityMessengerRouter;
import dev.belikhun.luna.messenger.velocity.service.VelocityMessengerStateStore;
import dev.belikhun.luna.messenger.velocity.service.WebhookDiscordBridgeGateway;
import dev.belikhun.luna.messenger.velocity.service.discord.DiscordCommandRegistry;
import dev.belikhun.luna.messenger.velocity.service.discord.LinkDiscordCommandHandler;
import dev.belikhun.luna.messenger.velocity.service.discord.UnlinkDiscordCommandHandler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@Plugin(
	id = "lunamessenger",
	name = "LunaMessenger",
	version = BuildConstants.VERSION,
	description = "Centralized messenger and cross-server chat for Luna network",
	dependencies = {
		@Dependency(id = "lunacore"),
		@Dependency(id = "miniplaceholders", optional = true)
	},
	authors = {"Belikhun"}
)
public final class LunaMessengerVelocityPlugin {
	private final ProxyServer proxyServer;
	private final Path dataDirectory;
	private final LunaLogger logger;
	private final long startedAtEpochMs;
	private PluginMessageBus<Object, Object> bus;
	private VelocityMessengerRouter router;
	private DiscordBridgeGateway discordBridge;
	private VelocityMessengerStateStore stateStore;
	private DiscordAccountLinkService discordAccountLinkService;
	private DiscordCommandRegistry discordCommandRegistry;

	@Inject
	public LunaMessengerVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.startedAtEpochMs = System.currentTimeMillis();
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaMessengerVelocity"), true).scope("MessengerVelocity");
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		LuckPermsService luckPermsService = LunaCoreVelocity.services().dependencyManager().resolve(LuckPermsService.class);
		Database sharedDatabase = LunaCoreVelocity.services().dependencyManager().resolveOptional(Database.class).orElse(null);
		discordAccountLinkService = DiscordAccountLinkService.create(sharedDatabase, dataDirectory.resolve("config.yml"), proxyServer, luckPermsService, logger);
		discordCommandRegistry = createDiscordCommandRegistry(discordAccountLinkService);
		VelocityMessengerConfig config = VelocityMessengerConfig.load(dataDirectory.resolve("config.yml"));
		bus = LunaCoreVelocity.services().dependencyManager().resolve(VelocityPluginMessagingBus.class);
		discordBridge = createDiscordGateway(config, discordCommandRegistry);
		router = new VelocityMessengerRouter(proxyServer, logger, bus, config, new SimpleTemplateRenderer(), luckPermsService, discordBridge);
		stateStore = new VelocityMessengerStateStore(dataDirectory.resolve("state.bin"), logger);
		router.restorePersistentState(stateStore.load());
		router.registerChannels();
		registerCommand();
		proxyServer.getEventManager().register(this, new MessengerPresenceListener(router));
		router.publishPresenceSnapshot();
		logger.audit("Thư mục dữ liệu messenger: " + dataDirectory.toAbsolutePath());
		logger.audit("Discord bridge enabled: " + config.discord().enabled());
		logger.success("LunaMessenger (Velocity) đã khởi động thành công.");
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		if (router != null && stateStore != null) {
			stateStore.save(router.snapshotPersistentState());
		}
		if (router != null) {
			router.close();
		}
		if (discordBridge != null) {
			discordBridge.close();
		}
		if (discordAccountLinkService != null) {
			discordAccountLinkService.close();
		}
		logger.audit("LunaMessenger (Velocity) đã tắt.");
	}

	private void ensureDefaults() {
		LunaYamlConfig.ensureFile(dataDirectory.resolve("config.yml"), () -> getClass().getClassLoader().getResourceAsStream("config.yml"));
	}

	private void registerCommand() {
		CommandManager manager = proxyServer.getCommandManager();
		CommandMeta meta = manager.metaBuilder("lunamessenger")
			.aliases("lmsg")
			.plugin(this)
			.build();
		manager.register(meta, new MessengerAdminCommand(this::reloadRuntimeConfig));

		CommandMeta muteMeta = manager.metaBuilder("mute")
			.plugin(this)
			.build();
		manager.register(muteMeta, new MessengerModerationCommand(proxyServer, router, MessengerModerationCommand.Action.MUTE));

		CommandMeta unmuteMeta = manager.metaBuilder("unmute")
			.plugin(this)
			.build();
		manager.register(unmuteMeta, new MessengerModerationCommand(proxyServer, router, MessengerModerationCommand.Action.UNMUTE));

		CommandMeta muteCheckMeta = manager.metaBuilder("mutecheck")
			.plugin(this)
			.build();
		manager.register(muteCheckMeta, new MessengerModerationCommand(proxyServer, router, MessengerModerationCommand.Action.MUTECHECK));

		CommandMeta warnMeta = manager.metaBuilder("warn")
			.plugin(this)
			.build();
		manager.register(warnMeta, new MessengerModerationCommand(proxyServer, router, MessengerModerationCommand.Action.WARN));

		CommandMeta broadcastMeta = manager.metaBuilder("broadcast")
			.aliases("bc")
			.plugin(this)
			.build();
		manager.register(broadcastMeta, new MessengerBroadcastCommand(router));

		CommandMeta spyMeta = manager.metaBuilder("spy")
			.plugin(this)
			.build();
		manager.register(spyMeta, new MessengerSpyCommand(proxyServer, router));

		CommandMeta discordMeta = manager.metaBuilder("discord")
			.plugin(this)
			.build();
		manager.register(discordMeta, new DiscordLinkCommand(proxyServer, () -> discordAccountLinkService));
	}

	private synchronized void reloadRuntimeConfig() {
		ensureDefaults();
		VelocityMessengerConfig newConfig = VelocityMessengerConfig.load(dataDirectory.resolve("config.yml"));
		LuckPermsService luckPermsService = LunaCoreVelocity.services().dependencyManager().resolve(LuckPermsService.class);
		Database sharedDatabase = LunaCoreVelocity.services().dependencyManager().resolveOptional(Database.class).orElse(null);
		DiscordAccountLinkService newLinkService = DiscordAccountLinkService.create(sharedDatabase, dataDirectory.resolve("config.yml"), proxyServer, luckPermsService, logger);
		DiscordCommandRegistry newCommandRegistry = createDiscordCommandRegistry(newLinkService);
		DiscordBridgeGateway newBridge = createDiscordGateway(newConfig, newCommandRegistry);
		DiscordBridgeGateway oldBridge = this.discordBridge;
		DiscordAccountLinkService oldLinkService = this.discordAccountLinkService;

		this.discordAccountLinkService = newLinkService;
		this.discordCommandRegistry = newCommandRegistry;
		this.discordBridge = newBridge;
		if (router != null) {
			router.reloadRuntime(newConfig, newBridge);
		}

		if (oldBridge != null) {
			oldBridge.close();
		}
		if (oldLinkService != null) {
			oldLinkService.close();
		}

		logger.audit("Đã tải lại cấu hình LunaMessenger từ config.yml.");
	}

	private DiscordBridgeGateway createDiscordGateway(VelocityMessengerConfig config, DiscordCommandRegistry commandRegistry) {
		VelocityMessengerConfig.DiscordConfig discord = config.discord();
		if (!discord.enabled()) {
			return new NoopDiscordBridgeGateway(logger);
		}

		DiscordBridgeGateway webhookGateway = null;
		if (!discord.webhookUrls().isEmpty()) {
			webhookGateway = new WebhookDiscordBridgeGateway(logger, discord.webhookUrls(), discord.retry());
		}

		VelocityMessengerConfig.DiscordBotConfig bot = discord.bot();
		if (bot != null && bot.enabled()) {
			if (bot.token() == null || bot.token().isBlank() || bot.channelIds().isEmpty()) {
				logger.warn("Discord bot mode được bật nhưng thiếu bot.token hoặc bot.channel-ids. Chỉ dùng webhook nếu có.");
				if (webhookGateway != null) {
					return new RoutingDiscordBridgeGateway(logger, webhookGateway, null);
				}
				return new NoopDiscordBridgeGateway(logger);
			}

			try {
				DiscordBridgeGateway botGateway = new JdaDiscordBridgeGateway(logger, bot, discord.retry(), message -> {
					if (router != null) {
						router.routeInboundDiscordMessage(message);
					}
				}, discord.webhookUrls(), this::presencePlaceholders, commandRegistry);
				return new RoutingDiscordBridgeGateway(logger, webhookGateway, botGateway);
			} catch (Exception exception) {
				logger.warn("Không thể khởi tạo Discord bot gateway: " + exception.getMessage());
				if (webhookGateway != null) {
					return new RoutingDiscordBridgeGateway(logger, webhookGateway, null);
				}
				return new NoopDiscordBridgeGateway(logger);
			}
		}

		if (webhookGateway == null) {
			logger.warn("Discord bridge được bật nhưng thiếu webhook.urls. Sử dụng noop gateway.");
			return new NoopDiscordBridgeGateway(logger);
		}
		return new RoutingDiscordBridgeGateway(logger, webhookGateway, null);
	}

	private DiscordCommandRegistry createDiscordCommandRegistry(DiscordAccountLinkService linkService) {
		DiscordCommandRegistry registry = new DiscordCommandRegistry(logger);
		registry.register(new LinkDiscordCommandHandler(logger, proxyServer, linkService));
		registry.register(new UnlinkDiscordCommandHandler(logger, proxyServer, linkService));
		return registry;
	}

	private Map<String, String> presencePlaceholders() {
		Map<String, String> values = new HashMap<>();
		long now = System.currentTimeMillis();
		long uptimeMs = Math.max(0L, now - startedAtEpochMs);
		long uptimeSeconds = uptimeMs / 1000L;
		int playerCount = proxyServer.getPlayerCount();
		int totalServers = proxyServer.getAllServers().size();
		int onlineServers = (int) proxyServer.getAllServers().stream()
			.filter(server -> !server.getPlayersConnected().isEmpty())
			.count();

		values.put("playerlist_count", Integer.toString(playerCount));
		values.put("player_count", Integer.toString(playerCount));
		values.put("online_players", Integer.toString(playerCount));
		values.put("online_servers", Integer.toString(onlineServers));
		values.put("registered_servers", Integer.toString(totalServers));
		values.put("total_servers", Integer.toString(totalServers));
		values.put("uptime_ms", Long.toString(uptimeMs));
		values.put("uptime_seconds", Long.toString(uptimeSeconds));
		values.put("uptime_minutes", Long.toString(uptimeSeconds / 60L));
		values.put("uptime_hours", Long.toString(uptimeSeconds / 3600L));
		values.put("uptime", Formatters.compactDuration(Duration.ofSeconds(uptimeSeconds)));
		return values;
	}
}
