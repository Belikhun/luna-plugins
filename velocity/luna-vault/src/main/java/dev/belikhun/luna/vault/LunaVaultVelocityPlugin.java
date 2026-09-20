package dev.belikhun.luna.vault;

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
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.VelocityHttpServerManager;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityForwardingSecretResolver;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;
import dev.belikhun.luna.vault.api.rpc.VaultRpcProtocol;
import dev.belikhun.luna.vault.command.BalTopCommand;
import dev.belikhun.luna.vault.command.BalanceCommand;
import dev.belikhun.luna.vault.command.EcoAdminCommand;
import dev.belikhun.luna.vault.command.PayCommand;
import dev.belikhun.luna.vault.http.VaultHttpEndpoints;
import dev.belikhun.luna.vault.placeholder.VelocityVaultMiniPlaceholders;
import dev.belikhun.luna.vault.placeholder.VelocityVaultTabPlaceholders;
import dev.belikhun.luna.vault.rpc.VaultRpcHandler;
import dev.belikhun.luna.vault.service.VelocityVaultConfig;
import dev.belikhun.luna.vault.service.VelocityVaultService;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@Plugin(
	id = "lunavault",
	name = "LunaVault",
	version = BuildConstants.VERSION,
	description = "Network-wide economy authority for Luna",
	dependencies = {
		@Dependency(id = "lunacore"),
		@Dependency(id = "miniplaceholders", optional = true),
		@Dependency(id = "tab", optional = true)
	},
	authors = {"Belikhun"}
)
public final class LunaVaultVelocityPlugin {
	private final ProxyServer proxyServer;
	private final Path dataDirectory;
	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private VelocityPluginMessagingBus pluginMessagingBus;
	private VelocityVaultService vaultService;
	private VelocityVaultMiniPlaceholders miniPlaceholders;
	private VelocityVaultTabPlaceholders tabPlaceholders;
	private EventHandler<TabLoadEvent> tabLoadHandler;
	private VaultRpcHandler rpcHandler;

	@Inject
	public LunaVaultVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaVaultVelocity"), true).scope("VaultVelocity");
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		ensureTabReloadHookRegistered();
		dependencyManager = LunaCoreVelocity.services().dependencyManager();
		VelocityVaultConfig config = VelocityVaultConfig.load(dataDirectory.resolve("config.yml"));
		Database database = dependencyManager.resolveOptional(Database.class).orElse(new NoopDatabase());
		try {
			DatabaseMigrator migrator = new DatabaseMigrator(database, logger.scope("Migration"));
			VaultDatabaseMigrations.register(migrator);
			migrator.migrateNamespace("lunavault");
		} catch (Exception exception) {
			logger.error("Không thể chuẩn bị schema cho LunaVault.", exception);
		}

		pluginMessagingBus = LunaCoreVelocity.services().pluginMessagingBus();
		vaultService = new VelocityVaultService(proxyServer, database, logger, config, pluginMessagingBus);
		rpcHandler = new VaultRpcHandler(proxyServer, logger, vaultService, pluginMessagingBus);
		rpcHandler.register();

		dependencyManager.registerSingleton(LunaVaultApi.class, vaultService);
		registerCommands();
		registerHttpEndpoints();
		registerMiniPlaceholders();
		registerTabPlaceholders();
		logger.success("LunaVault đã khởi động với vai trò sổ cái duy nhất của mạng (giao thức RPC v" + VaultRpcProtocol.VERSION + ").");
	}

	private void ensureDefaults() {
		Path configPath = dataDirectory.resolve("config.yml");
		LunaYamlConfig.ensureFile(configPath, () -> getClass().getClassLoader().getResourceAsStream("config.yml"));
		try (InputStream defaultsStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
			if (defaultsStream == null) {
				return;
			}

			Map<String, Object> defaults = LunaYamlConfig.loadMap(defaultsStream);
			if (defaults.isEmpty()) {
				return;
			}

			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(configPath));
			if (LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(configPath, current);
				logger.audit("Đã bổ sung các khóa cấu hình còn thiếu cho LunaVault.");
			}
		} catch (RuntimeException | java.io.IOException exception) {
			logger.warn("Không thể đồng bộ config.yml của LunaVault: " + exception.getMessage());
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		unregisterTabReloadHook();
		if (miniPlaceholders != null) {
			miniPlaceholders.unregister();
			miniPlaceholders = null;
		}
		if (tabPlaceholders != null) {
			tabPlaceholders.unregister();
			tabPlaceholders = null;
		}
		if (rpcHandler != null) {
			rpcHandler.unregister();
			rpcHandler = null;
		}
		if (vaultService != null) {
			vaultService.shutdown();
		}
		if (dependencyManager != null) {
			dependencyManager.unregister(LunaVaultApi.class);
			dependencyManager = null;
		}
	}

	private void registerCommands() {
		CommandManager manager = proxyServer.getCommandManager();
		CommandMeta balanceMeta = manager.metaBuilder("balance")
			.aliases("bal", "money")
			.plugin(this)
			.build();
		manager.register(balanceMeta, new BalanceCommand(vaultService));

		CommandMeta payMeta = manager.metaBuilder("pay")
			.plugin(this)
			.build();
		manager.register(payMeta, new PayCommand(proxyServer, vaultService));

		CommandMeta baltopMeta = manager.metaBuilder("baltop")
			.aliases("topbalance")
			.plugin(this)
			.build();
		manager.register(baltopMeta, new BalTopCommand(vaultService));

		CommandMeta ecoMeta = manager.metaBuilder("eco")
			.plugin(this)
			.build();
		manager.register(ecoMeta, new EcoAdminCommand(vaultService));
	}

	/**
	 * Expose the balance and the transaction history to the control console.
	 *
	 * The routes ride LunaCore's HTTP server and its forwarding-secret gate, so a
	 * missing secret costs the endpoints and nothing else — the economy itself
	 * keeps running.
	 */
	private void registerHttpEndpoints() {
		VelocityHttpServerManager httpServerManager = LunaCoreVelocity.services().httpServerManager();

		if (httpServerManager == null) {
			logger.warn("LunaCore không có HTTP server — bỏ qua endpoint vault.");
			return;
		}

		String secret = VelocityForwardingSecretResolver.resolve(dataDirectory, logger);
		RequestAuthorizer authorizer = new RequestAuthorizer(secret);

		if (!authorizer.configured()) {
			logger.warn("Chưa có forwarding secret — endpoint vault sẽ trả về 401.");
		}

		new VaultHttpEndpoints(logger, proxyServer, vaultService, authorizer)
			.register(httpServerManager.router());

		logger.audit("Đã đăng ký endpoint /vault/accounts/{player}.");
	}

	private void registerMiniPlaceholders() {
		if (proxyServer.getPluginManager().getPlugin("miniplaceholders").isEmpty()) {
			logger.audit("MiniPlaceholders chưa được cài trên proxy. Bỏ qua namespace lunavaultv.");
			return;
		}

		try {
			miniPlaceholders = new VelocityVaultMiniPlaceholders(logger, vaultService);
			miniPlaceholders.register();
		} catch (Throwable throwable) {
			logger.warn("Không thể đăng ký MiniPlaceholders namespace lunavaultv: " + throwable.getMessage());
			miniPlaceholders = null;
		}
	}

	private void registerTabPlaceholders() {
		if (proxyServer.getPluginManager().getPlugin("tab").isEmpty()) {
			logger.audit("TAB chưa được cài trên proxy. Bỏ qua placeholder %lunavaultv-balance%.");
			return;
		}

		try {
			tabPlaceholders = new VelocityVaultTabPlaceholders(logger, vaultService);
			tabPlaceholders.register();
		} catch (Throwable throwable) {
			logger.warn("Không thể đăng ký TAB placeholder %lunavaultv-balance%: " + throwable.getMessage());
			tabPlaceholders = null;
		}
	}

	private void ensureTabReloadHookRegistered() {
		if (tabLoadHandler != null || proxyServer.getPluginManager().getPlugin("tab").isEmpty()) {
			return;
		}

		try {
			if (TabAPI.getInstance().getEventBus() == null) {
				logger.warn("TAB API event bus không khả dụng. Không thể tự động đăng ký lại placeholder LunaVault sau /tab reload.");
				return;
			}

			tabLoadHandler = event -> {
				if (vaultService == null) {
					return;
				}

				try {
					registerTabPlaceholders();
					logger.audit("Đã đăng ký lại TAB placeholder của LunaVault sau /tab reload.");
				} catch (Throwable throwable) {
					logger.warn("Không thể đăng ký lại TAB placeholder của LunaVault sau /tab reload: " + throwable.getMessage());
				}
			};
			TabAPI.getInstance().getEventBus().register(TabLoadEvent.class, tabLoadHandler);
		} catch (Throwable throwable) {
			tabLoadHandler = null;
			logger.warn("Không thể gắn listener TabLoadEvent cho LunaVault: " + throwable.getMessage());
		}
	}

	private void unregisterTabReloadHook() {
		if (tabLoadHandler == null) {
			return;
		}

		try {
			if (TabAPI.getInstance().getEventBus() != null) {
				TabAPI.getInstance().getEventBus().unregister(tabLoadHandler);
			}
		} catch (Throwable ignored) {
		}

		tabLoadHandler = null;
	}
}
