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
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.messenger.velocity.command.MessengerAdminCommand;
import dev.belikhun.luna.messenger.velocity.command.MessengerBroadcastCommand;
import dev.belikhun.luna.messenger.velocity.command.MessengerModerationCommand;
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

import java.nio.file.Path;
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
	private PluginMessageBus<Object, Object> bus;
	private VelocityMessengerRouter router;
	private DiscordBridgeGateway discordBridge;
	private VelocityMessengerStateStore stateStore;

	@Inject
	public LunaMessengerVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaMessengerVelocity"), true).scope("MessengerVelocity");
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		VelocityMessengerConfig config = VelocityMessengerConfig.load(dataDirectory.resolve("config.yml"));
		bus = new VelocityPluginMessagingBus(proxyServer, this, logger);
		discordBridge = createDiscordGateway(config);
		LuckPermsService luckPermsService = LunaCoreVelocity.services().dependencyManager().resolve(LuckPermsService.class);
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
		if (bus != null) {
			bus.close();
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
	}

	private synchronized void reloadRuntimeConfig() {
		ensureDefaults();
		VelocityMessengerConfig newConfig = VelocityMessengerConfig.load(dataDirectory.resolve("config.yml"));
		DiscordBridgeGateway newBridge = createDiscordGateway(newConfig);
		DiscordBridgeGateway oldBridge = this.discordBridge;

		this.discordBridge = newBridge;
		if (router != null) {
			router.reloadRuntime(newConfig, newBridge);
		}

		if (oldBridge != null) {
			oldBridge.close();
		}

		logger.audit("Đã tải lại cấu hình LunaMessenger từ config.yml.");
	}

	private DiscordBridgeGateway createDiscordGateway(VelocityMessengerConfig config) {
		VelocityMessengerConfig.DiscordConfig discord = config.discord();
		if (!discord.enabled()) {
			return new NoopDiscordBridgeGateway(logger);
		}

		DiscordBridgeGateway webhookGateway = null;
		if (discord.webhookUrl() != null && !discord.webhookUrl().isBlank()) {
			webhookGateway = new WebhookDiscordBridgeGateway(logger, discord.webhookUrl(), discord.retry());
		}

		VelocityMessengerConfig.DiscordBotConfig bot = discord.bot();
		if (bot != null && bot.enabled()) {
			if (bot.token() == null || bot.token().isBlank() || bot.channelId() == null || bot.channelId().isBlank()) {
				logger.warn("Discord bot mode được bật nhưng thiếu bot.token hoặc bot.channel-id. Chỉ dùng webhook nếu có.");
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
				});
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
			logger.warn("Discord bridge được bật nhưng thiếu webhook.url. Sử dụng noop gateway.");
			return new NoopDiscordBridgeGateway(logger);
		}
		return new RoutingDiscordBridgeGateway(logger, webhookGateway, null);
	}
}
