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
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;
import dev.belikhun.luna.vault.api.rpc.VaultRpcAction;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;
import dev.belikhun.luna.vault.command.BalTopCommand;
import dev.belikhun.luna.vault.command.BalanceCommand;
import dev.belikhun.luna.vault.command.EcoAdminCommand;
import dev.belikhun.luna.vault.command.PayCommand;
import dev.belikhun.luna.vault.placeholder.VelocityVaultMiniPlaceholders;
import dev.belikhun.luna.vault.placeholder.VelocityVaultTabPlaceholders;
import dev.belikhun.luna.vault.service.VelocityVaultConfig;
import dev.belikhun.luna.vault.service.VelocityVaultService;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
	private final Map<UUID, CachedOperationResult> operationResults = new ConcurrentHashMap<>();
	private final Map<String, Long> backendSessionVersions = new ConcurrentHashMap<>();
	private static final int MAX_OPERATION_RESULT_CACHE = 8192;
	private static final long OPERATION_RESULT_TTL_MILLIS = 5L * 60L * 1000L;
	private final AtomicLong dedupHitCount = new AtomicLong();
	private final AtomicLong staleSessionRejectCount = new AtomicLong();

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
		pluginMessagingBus.registerOutgoing(VaultChannels.CACHE_SYNC);
		pluginMessagingBus.registerOutgoing(VaultChannels.RPC);
		pluginMessagingBus.registerIncoming(VaultChannels.RPC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			VaultRpcRequest request = VaultRpcRequest.readFrom(reader);
			VaultRpcResponse response = handleRequest(request);
			pluginMessagingBus.send(context.source(), VaultChannels.RPC, writer -> response.writeTo(writer));
			return PluginMessageDispatchResult.HANDLED;
		});

		dependencyManager.registerSingleton(LunaVaultApi.class, vaultService);
		registerCommands();
		registerMiniPlaceholders();
		registerTabPlaceholders();
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
		unregisterTabReloadHook();
		if (miniPlaceholders != null) {
			miniPlaceholders.unregister();
			miniPlaceholders = null;
		}
		if (tabPlaceholders != null) {
			tabPlaceholders.unregister();
			tabPlaceholders = null;
		}
		if (pluginMessagingBus != null) {
			pluginMessagingBus.unregisterIncoming(VaultChannels.RPC);
			pluginMessagingBus.unregisterOutgoing(VaultChannels.CACHE_SYNC);
			pluginMessagingBus.unregisterOutgoing(VaultChannels.RPC);
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

	private VaultRpcResponse handleRequest(VaultRpcRequest request) {
		try {
			pruneOperationResults();
			if (isMutatingAction(request.action())) {
				UUID operationId = request.operationId();
				if (operationId != null) {
					VaultOperationResult cached = readCachedOperation(operationId);
					if (cached != null) {
						long dedup = dedupHitCount.incrementAndGet();
						if (dedup % 50L == 0L) {
							logger.audit("LunaVault RPC dedup hits=" + dedup + " staleRejects=" + staleSessionRejectCount.get());
						}
						return emptyPageResponse(request, cached);
					}
				}

				VaultOperationResult staleSession = validateAndTrackSession(request);
				if (staleSession != null) {
					long rejects = staleSessionRejectCount.incrementAndGet();
					if (rejects % 25L == 0L) {
						logger.warn("LunaVault RPC stale-session rejects=" + rejects + " dedupHits=" + dedupHitCount.get());
					}
					return emptyPageResponse(request, staleSession);
				}
			}

			VaultRpcResponse response = switch (request.action()) {
				case SNAPSHOT -> {
					VaultPlayerSnapshot snapshot = vaultService.snapshot(request.playerId(), request.playerName()).join();
					yield new VaultRpcResponse(
						request.correlationId(),
						VaultOperationResult.success(null, snapshot.balanceMinor(), null),
						snapshot,
						VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
					);
				}
				case BALANCE -> new VaultRpcResponse(
					request.correlationId(),
					VaultOperationResult.success(null, vaultService.balance(request.playerId(), request.playerName()).join(), null),
					null,
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case DEPOSIT -> new VaultRpcResponse(
					request.correlationId(),
					vaultService.deposit(request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details()).join(),
					null,
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case WITHDRAW -> new VaultRpcResponse(
					request.correlationId(),
					vaultService.withdraw(request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details()).join(),
					null,
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case TRANSFER -> new VaultRpcResponse(
					request.correlationId(),
					vaultService.transfer(request.playerId(), request.playerName(), request.targetId(), request.targetName(), request.amountMinor(), request.source(), request.details()).join(),
					null,
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case SET_BALANCE -> new VaultRpcResponse(
					request.correlationId(),
					vaultService.setBalance(request.actorId(), request.actorName(), request.playerId(), request.playerName(), request.amountMinor(), request.source(), request.details()).join(),
					null,
					VaultTransactionPage.empty(0, Math.max(1, request.pageSize()))
				);
				case HISTORY -> new VaultRpcResponse(
					request.correlationId(),
					VaultOperationResult.success(null, vaultService.balance(request.playerId(), request.playerName()).join(), null),
					null,
					vaultService.history(request.playerId(), request.page(), request.pageSize()).join()
				);
			};

			if (isMutatingAction(request.action()) && request.operationId() != null) {
				cacheOperationResult(request.operationId(), response.result());
			}

			return response;
		} catch (Exception exception) {
			logger.error("Xử lý RPC của LunaVault thất bại.", exception);
			return new VaultRpcResponse(
				request.correlationId(),
				VaultOperationResult.failed(VaultFailureReason.INTERNAL_ERROR, "Yêu cầu kinh tế thất bại ở proxy.", 0L),
				null,
				VaultTransactionPage.empty(request.page(), Math.max(1, request.pageSize()))
			);
		}
	}

	private VaultRpcResponse emptyPageResponse(VaultRpcRequest request, VaultOperationResult result) {
		return new VaultRpcResponse(
			request.correlationId(),
			result,
			null,
			VaultTransactionPage.empty(request.page(), Math.max(1, request.pageSize()))
		);
	}

	private boolean isMutatingAction(VaultRpcAction action) {
		return action == VaultRpcAction.DEPOSIT
			|| action == VaultRpcAction.WITHDRAW
			|| action == VaultRpcAction.TRANSFER
			|| action == VaultRpcAction.SET_BALANCE;
	}

	private VaultOperationResult validateAndTrackSession(VaultRpcRequest request) {
		if (request.source() != null && "backend-sync".equalsIgnoreCase(request.source().trim())) {
			return null;
		}

		if (request.playerId() == null || request.sessionVersion() <= 0L || request.backendId() == null || request.backendId().isBlank()) {
			return null;
		}

		String sessionKey = request.backendId() + "|" + request.playerId();
		Long known = backendSessionVersions.get(sessionKey);
		if (known != null && request.sessionVersion() < known) {
			return VaultOperationResult.failed(
				VaultFailureReason.STALE_SESSION,
				"Phiên backend đã hết hạn, vui lòng đồng bộ lại snapshot.",
				0L
			);
		}

		backendSessionVersions.put(sessionKey, request.sessionVersion());
		return null;
	}

	private void cacheOperationResult(UUID operationId, VaultOperationResult result) {
		if (operationId == null || result == null) {
			return;
		}

		operationResults.put(operationId, new CachedOperationResult(result, System.currentTimeMillis() + OPERATION_RESULT_TTL_MILLIS));
		if (operationResults.size() > MAX_OPERATION_RESULT_CACHE) {
			Iterator<UUID> iterator = operationResults.keySet().iterator();
			while (operationResults.size() > MAX_OPERATION_RESULT_CACHE && iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			}
		}
	}

	private VaultOperationResult readCachedOperation(UUID operationId) {
		CachedOperationResult cached = operationResults.get(operationId);
		if (cached == null) {
			return null;
		}

		if (cached.expiresAtEpochMillis() < System.currentTimeMillis()) {
			operationResults.remove(operationId, cached);
			return null;
		}

		return cached.result();
	}

	private void pruneOperationResults() {
		if (operationResults.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		operationResults.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMillis() < now);
	}

	private record CachedOperationResult(VaultOperationResult result, long expiresAtEpochMillis) {
	}
}
