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
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;
import dev.belikhun.luna.vault.api.rpc.VaultRpcAction;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;
import dev.belikhun.luna.vault.command.EcoAdminCommand;
import dev.belikhun.luna.vault.command.PayCommand;
import dev.belikhun.luna.vault.service.VelocityVaultConfig;
import dev.belikhun.luna.vault.service.VelocityVaultService;

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
		@Dependency(id = "lunacore")
	},
	authors = {"Belikhun"}
)
public final class LunaVaultVelocityPlugin {
	private final ProxyServer proxyServer;
	private final Path dataDirectory;
	private final LunaLogger logger;
	private VelocityPluginMessagingBus pluginMessagingBus;
	private VelocityVaultService vaultService;

	@Inject
	public LunaVaultVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaVaultVelocity"), true).scope("VaultVelocity");
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		VelocityVaultConfig config = VelocityVaultConfig.load(dataDirectory.resolve("config.yml"));
		Database database = LunaCoreVelocity.services().dependencyManager().resolveOptional(Database.class).orElse(new NoopDatabase());
		try {
			DatabaseMigrator migrator = new DatabaseMigrator(database, logger.scope("Migration"));
			VaultDatabaseMigrations.register(migrator);
			migrator.migrateNamespace("lunavault");
		} catch (Exception exception) {
			logger.error("Không thể chuẩn bị schema cho LunaVault.", exception);
		}

		vaultService = new VelocityVaultService(proxyServer, database, logger, config);
		pluginMessagingBus = LunaCoreVelocity.services().pluginMessagingBus();
		pluginMessagingBus.registerOutgoing(VaultChannels.RPC);
		pluginMessagingBus.registerIncoming(VaultChannels.RPC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String kind = reader.readUtf();
			if (!"request".equals(kind)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			VaultRpcRequest request = VaultRpcRequest.readFrom(reader);
			VaultRpcResponse response = handleRequest(request);
			pluginMessagingBus.send(context.source(), VaultChannels.RPC, writer -> response.writeTo(writer));
			return PluginMessageDispatchResult.HANDLED;
		});

		LunaCoreVelocity.services().dependencyManager().registerSingleton(LunaVaultApi.class, vaultService);
		registerCommands();
		logger.success("LunaVault đã khởi động với vai trò source-of-truth trên Velocity.");
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
		if (pluginMessagingBus != null) {
			pluginMessagingBus.unregisterIncoming(VaultChannels.RPC);
			pluginMessagingBus.unregisterOutgoing(VaultChannels.RPC);
		}
		LunaCoreVelocity.services().dependencyManager().unregister(LunaVaultApi.class);
	}

	private void registerCommands() {
		CommandManager manager = proxyServer.getCommandManager();
		CommandMeta payMeta = manager.metaBuilder("pay")
			.plugin(this)
			.build();
		manager.register(payMeta, new PayCommand(proxyServer, vaultService));

		CommandMeta ecoMeta = manager.metaBuilder("eco")
			.plugin(this)
			.build();
		manager.register(ecoMeta, new EcoAdminCommand(vaultService));
	}

	private VaultRpcResponse handleRequest(VaultRpcRequest request) {
		try {
			return switch (request.action()) {
				case BALANCE -> new VaultRpcResponse(
					request.correlationId(),
					VaultRpcAction.BALANCE,
					VaultOperationResult.success(null, vaultService.balance(request.playerId(), request.playerName()).join(), null),
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case DEPOSIT -> new VaultRpcResponse(
					request.correlationId(),
					VaultRpcAction.DEPOSIT,
					vaultService.deposit(request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details()).join(),
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case WITHDRAW -> new VaultRpcResponse(
					request.correlationId(),
					VaultRpcAction.WITHDRAW,
					vaultService.withdraw(request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details()).join(),
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case TRANSFER -> new VaultRpcResponse(
					request.correlationId(),
					VaultRpcAction.TRANSFER,
					vaultService.transfer(request.playerId(), request.playerName(), request.targetId(), request.targetName(), request.amountMinor(), request.source(), request.details()).join(),
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case SET_BALANCE -> new VaultRpcResponse(
					request.correlationId(),
					VaultRpcAction.SET_BALANCE,
					vaultService.setBalance(request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details()).join(),
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case HISTORY -> new VaultRpcResponse(
					request.correlationId(),
					VaultRpcAction.HISTORY,
					VaultOperationResult.success(null, vaultService.balance(request.playerId(), request.playerName()).join(), null),
					vaultService.history(request.playerId(), request.page(), request.pageSize()).join()
				);
			};
		} catch (Exception exception) {
			logger.error("Xử lý RPC của LunaVault thất bại.", exception);
			return new VaultRpcResponse(
				request.correlationId(),
				request.action(),
				VaultOperationResult.failed(VaultFailureReason.INTERNAL_ERROR, "Yêu cầu kinh tế thất bại ở proxy.", 0L),
				VaultTransactionPage.empty(request.page(), Math.max(1, request.pageSize()))
			);
		}
	}
}
