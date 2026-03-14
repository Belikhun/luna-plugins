package dev.belikhun.luna.auth;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.auth.command.AuthAdminCommand;
import dev.belikhun.luna.auth.command.LogoutCommand;
import dev.belikhun.luna.auth.messaging.AuthChannels;
import dev.belikhun.luna.auth.service.AuthRepository;
import dev.belikhun.luna.auth.service.AuthService;
import dev.belikhun.luna.auth.service.MojangPremiumCheckService;
import dev.belikhun.luna.auth.service.Pbkdf2PasswordHasher;
import dev.belikhun.luna.core.api.auth.OfflineUuid;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(
	id = "lunaauth",
	name = "LunaAuth",
	version = BuildConstants.VERSION,
	description = "Luna authentication authority for Velocity",
	dependencies = {
		@Dependency(id = "lunacore")
	},
	authors = {"Belikhun"}
)
public final class LunaAuthVelocityPlugin {
	private static final int BACKEND_SYNC_RETRY_MAX_ATTEMPTS = 20;
	private static final long BACKEND_SYNC_RETRY_DELAY_MILLIS = 250L;

	private final ProxyServer proxyServer;
	private final Path dataDirectory;
	private LunaLogger logger;
	private VelocityPluginMessagingBus pluginMessagingBus;
	private AuthService authService;
	private boolean mixedModeQuickLoginEnabled;
	private boolean premiumUuidEnabled;
	private Map<UUID, UUID> uuidOverrideMap;
	private boolean authFlowLogsEnabled;
	private boolean backendHandshakeRequired;
	private Set<String> requiredBackendNames;
	private String backendNotReadyMessage;
	private BackendStatusView backendStatusView;
	private volatile boolean lastBackendReady;
	private MojangPremiumCheckService mojangPremiumCheckService;
	private final Set<InetSocketAddress> verifiedOnlineSessions;
	private final ConcurrentMap<UUID, String> pendingBackendAuthMessages;
	private final Set<UUID> backendSyncRetryInFlight;
	private volatile boolean initialized;

	@Inject
	public LunaAuthVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.verifiedOnlineSessions = ConcurrentHashMap.newKeySet();
		this.pendingBackendAuthMessages = new ConcurrentHashMap<>();
		this.backendSyncRetryInFlight = ConcurrentHashMap.newKeySet();
		this.uuidOverrideMap = Map.of();
		this.requiredBackendNames = Set.of();
		this.backendNotReadyMessage = "Hệ thống xác thực đang khởi tạo. Vui lòng thử lại sau vài giây.";
		this.lastBackendReady = false;
		this.initialized = false;
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		this.initialized = false;
		ensureDefaults();
		Path configPath = dataDirectory.resolve("config.yml");
		Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> logging = ConfigValues.map(root, "logging");
		Map<String, Object> auth = ConfigValues.map(root, "auth");
		Map<String, Object> hashing = ConfigValues.map(auth, "hashing");
		Map<String, Object> pbkdf2 = ConfigValues.map(hashing, "pbkdf2");
		Map<String, Object> quickLogin = ConfigValues.map(auth, "quick-login");
		Map<String, Object> mixedMode = ConfigValues.map(quickLogin, "mixed-mode");
		Map<String, Object> backendHandshake = ConfigValues.map(auth, "backend-handshake");

		boolean ansi = ConfigValues.booleanValue(logging, "ansi", true);
		boolean debug = ConfigValues.booleanValue(logging, "debug", false);
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaAuthVelocity"), ansi).withDebug(debug).scope("AuthVelocity");
		this.authFlowLogsEnabled = ConfigValues.booleanValue(logging, "auth-flow", true);
		this.mixedModeQuickLoginEnabled = ConfigValues.booleanValue(mixedMode, "enabled", true);
		this.premiumUuidEnabled = ConfigValues.booleanValue(mixedMode, "premium-uuid", true);
		this.uuidOverrideMap = parseUuidOverrideMap(ConfigValues.map(mixedMode, "uuid-override-map"));
		this.backendHandshakeRequired = ConfigValues.booleanValue(backendHandshake, "required", true);
		this.requiredBackendNames = parseRequiredBackends(backendHandshake.get("required-backends"));
		this.backendNotReadyMessage = ConfigValues.string(backendHandshake, "deny-message", "Hệ thống xác thực đang khởi tạo. Vui lòng thử lại sau vài giây.");
		int maxFailures = ConfigValues.intValue(auth, "max-failures", 5);
		long lockoutSeconds = Math.max(1, ConfigValues.intValue(auth, "lockout-seconds", 120));
		long sessionSeconds = Math.max(60, ConfigValues.intValue(auth, "session-after-disconnect-seconds", 900));
		int retentionDays = Math.max(1, ConfigValues.intValue(auth, "login-history-retention-days", 7));
		int hashIterations = Math.max(50_000, ConfigValues.intValue(pbkdf2, "iterations", 210_000));
		int hashSaltBytes = Math.max(16, ConfigValues.intValue(pbkdf2, "salt-bytes", 16));
		int hashKeyBits = Math.max(128, ConfigValues.intValue(pbkdf2, "key-bits", 256));
		long premiumCheckTimeoutMillis = Math.max(500L, ConfigValues.intValue(mixedMode, "premium-check-timeout-ms", 3000));
		long premiumNameCacheMinutes = Math.max(1L, ConfigValues.intValue(mixedMode, "premium-name-cache-minutes", 60));

		this.mojangPremiumCheckService = new MojangPremiumCheckService(logger, premiumCheckTimeoutMillis, premiumNameCacheMinutes, authFlowLogsEnabled);
		logger.audit("Khởi tạo LunaAuth Velocity: onlineMode=" + proxyServer.getConfiguration().isOnlineMode()
			+ ", mixedMode=" + mixedModeQuickLoginEnabled
			+ ", premiumUuid=" + premiumUuidEnabled
			+ ", uuidOverrideRules=" + uuidOverrideMap.size()
			+ ", backendHandshakeRequired=" + backendHandshakeRequired
			+ ", requiredBackends=" + requiredBackendNames
			+ ", authFlowLogs=" + authFlowLogsEnabled
			+ ", hashIterations=" + hashIterations
			+ ", hashSaltBytes=" + hashSaltBytes
			+ ", hashKeyBits=" + hashKeyBits);

		var services = LunaCoreVelocity.services();

		Database database = services.dependencyManager().resolve(Database.class);
		this.backendStatusView = services.backendStatusView();
		this.pluginMessagingBus = services.pluginMessagingBus();
		this.pluginMessagingBus.registerIncoming(AuthChannels.COMMAND_REQUEST, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"login".equals(action) && !"register".equals(action) && !"logout".equals(action) && !"sync_state".equals(action)) {
				flow("Bỏ qua command_request action không hỗ trợ: " + action);
				return PluginMessageDispatchResult.HANDLED;
			}

			java.util.UUID playerUuid = reader.readUuid();
			String username = reader.readUtf();
			flow("RX command_request action=" + action + " player=" + username + " uuid=" + playerUuid);
			Player player = proxyServer.getPlayer(playerUuid).orElse(null);
			if (player == null) {
				logger.warn("Nhận command_request nhưng player không còn online: uuid=" + playerUuid + " action=" + action);
				return PluginMessageDispatchResult.HANDLED;
			}

			if ("sync_state".equals(action)) {
				syncAuthState(player);
				scheduleBackendSyncRetry(playerUuid, 1);
				flow("Processed sync_state request player=" + username + " uuid=" + playerUuid);
				return PluginMessageDispatchResult.HANDLED;
			}

			String password = "logout".equals(action) ? "" : reader.readUtf();
			String confirm = "register".equals(action) ? reader.readUtf() : "";

			AuthService.AuthResult result;
			if ("login".equals(action)) {
				result = authService.login(playerUuid, username, ipAddress(player), password);
			} else if ("register".equals(action)) {
				result = authService.register(playerUuid, username, ipAddress(player), password, confirm);
			} else {
				result = authService.logout(playerUuid, username, ipAddress(player));
			}
			flow("Xử lý command_request xong action=" + action + " player=" + username + " success=" + result.success());
			syncAuthState(player);
			sendCommandResponse(player, result.success(), result.message());
			if ("logout".equals(action) && result.success()) {
				player.disconnect(Component.text("Bạn đã đăng xuất phiên hiện tại."));
			}
			return PluginMessageDispatchResult.HANDLED;
		});
		this.pluginMessagingBus.registerOutgoing(AuthChannels.AUTH_STATE);
		this.pluginMessagingBus.registerOutgoing(AuthChannels.COMMAND_RESPONSE);
		this.pluginMessagingBus.registerOutgoing(AuthChannels.ADMIN_REQUEST);

		AuthRepository repository = new AuthRepository(database);
		repository.ensureSchema();
		Pbkdf2PasswordHasher passwordHasher = new Pbkdf2PasswordHasher(hashIterations, hashSaltBytes, hashKeyBits);
		this.authService = new AuthService(logger, repository, passwordHasher, maxFailures, lockoutSeconds * 1000L, sessionSeconds * 1000L);
		this.authService.cleanupHistoryRetention(retentionDays);

		registerCommands();
		this.initialized = true;
		logger.success("LunaAuth Velocity đã khởi động thành công.");
		if (mixedModeQuickLoginEnabled && !proxyServer.getConfiguration().isOnlineMode()) {
			flow("Mixed-mode quick-login bật trên proxy offline-mode: premium force-online, cracked theo offline flow.");
		}
	}

	@Subscribe
	public void onPreLogin(PreLoginEvent event) {
		if (!isReady()) {
			event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(backendNotReadyMessage)));
			warnNotReady("PreLogin", event.getUsername());
			return;
		}

		if (!isBackendHandshakeReady()) {
			event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(backendNotReadyMessage)));
			warnNotReady("PreLogin.BackendHandshake", event.getUsername());
			return;
		}

		if (!event.getResult().isAllowed()) {
			flow("PreLogin bị bỏ qua vì event đã bị deny trước đó cho username=" + event.getUsername());
			return;
		}
		flow("Incoming login request username=" + event.getUsername() + " remote=" + event.getConnection().getRemoteAddress());
		if (proxyServer.getConfiguration().isOnlineMode()) {
			flow("Proxy global online-mode=true, bỏ qua mixed-mode premium check.");
			return;
		}
		if (!mixedModeQuickLoginEnabled) {
			flow("Mixed-mode quick-login đang tắt, không force onlinemode theo premium check.");
			return;
		}

		String username = event.getUsername();
		if (username == null || username.isBlank()) {
			flow("PreLogin username rỗng, bỏ qua premium check.");
			return;
		}

		boolean premium = mojangPremiumCheckService.isPremiumUsername(username);
		if (premium) {
			event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
			flow("PreLogin quyết định: premium username=" + username + " -> forceOnlineMode.");
		} else {
			flow("PreLogin quyết định: cracked username=" + username + " -> giữ offline-mode flow.");
		}
	}

	@Subscribe
	public void onGameProfileRequest(GameProfileRequestEvent event) {
		if (!isReady()) {
			return;
		}

		if (!event.isOnlineMode()) {
			UUID offlineUuid = event.getGameProfile().getId();
			Optional<UUID> claimedOnlineUuid = premiumUuidEnabled ? authService.findClaimedOnlineUuid(offlineUuid, event.getUsername()) : Optional.empty();
			if (claimedOnlineUuid.isPresent()) {
				UUID mappedUuid = claimedOnlineUuid.get();
				event.setGameProfile(event.getGameProfile().withId(mappedUuid));
				logger.audit("UUID mapping username=" + event.getUsername()
					+ " mode=CLAIMED_OFFLINE_MAP"
					+ " from=" + offlineUuid
					+ " to=" + mappedUuid
					+ " remote=" + event.getConnection().getRemoteAddress());
			} else {
				flow("GameProfileRequest username=" + event.getUsername() + " onlineMode=false, không có claimed map.");
			}
			return;
		}

		UUID verifiedUuid = event.getGameProfile().getId();
		UUID mappedUuid = uuidOverrideMap.get(verifiedUuid);
		UUID effectiveUuid = verifiedUuid;
		String mappingMode = "KEEP_PREMIUM_UUID";
		if (mappedUuid != null) {
			effectiveUuid = mappedUuid;
			mappingMode = "CONFIG_UUID_MAP";
		} else if (!premiumUuidEnabled) {
			effectiveUuid = OfflineUuid.fromUsername(event.getUsername());
			mappingMode = "OFFLINE_UUID_FALLBACK";
		}

		if (!effectiveUuid.equals(verifiedUuid)) {
			event.setGameProfile(event.getGameProfile().withId(effectiveUuid));
		}

		logger.audit("UUID mapping username=" + event.getUsername()
			+ " mode=" + mappingMode
			+ " from=" + verifiedUuid
			+ " to=" + effectiveUuid
			+ " remote=" + event.getConnection().getRemoteAddress());

		if (premiumUuidEnabled) {
			UUID offlineUuid = OfflineUuid.fromUsername(event.getUsername());
			authService.claimOfflineUuidMapping(event.getUsername(), offlineUuid, effectiveUuid);
			flow("Auto-claimed offline UUID mapping username=" + event.getUsername() + " offlineUuid=" + offlineUuid + " -> onlineUuid=" + effectiveUuid);
		}

		verifiedOnlineSessions.add(event.getConnection().getRemoteAddress());
		flow("GameProfileRequest verified online session username=" + event.getUsername()
			+ " uuid=" + event.getGameProfile().getId()
			+ " remote=" + event.getConnection().getRemoteAddress());
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		Player player = event.getPlayer();
		if (!isReady()) {
			warnNotReady("PostLogin", player.getUsername());
			player.disconnect(Component.text("Hệ thống xác thực chưa sẵn sàng. Vui lòng vào lại sau vài giây."));
			return;
		}

		AuthService.QuickAuthTrustDecision quickAuthTrustDecision = quickAuthDecision(player);
		flow("PostLogin player=" + player.getUsername() + " quickLoginTrusted=" + quickAuthTrustDecision.trusted()
			+ " reasonCode=" + quickAuthTrustDecision.reasonCode() + " remote=" + player.getRemoteAddress());
		AuthService.JoinDecision decision = authService.handleJoin(
			player.getUniqueId(),
			player.getUsername(),
			ipAddress(player),
			quickAuthTrustDecision
		);
		syncAuthState(player);
		flow("PostLogin decision player=" + player.getUsername() + " authenticated=" + decision.authenticated()
			+ " needsRegister=" + decision.needsRegister() + " locked=" + decision.locked());
		if (decision.authenticated()) {
			if (decision.needsRegister()) {
				player.sendRichMessage(decision.message());
			}

			if (!sendAuthenticatedBackendNotice(player, decision.message())) {
				pendingBackendAuthMessages.put(player.getUniqueId(), decision.message());
			}
			return;
		}
		if (decision.locked()) {
			player.sendRichMessage("<red>" + decision.message() + "</red>");
			return;
		}
		if (decision.needsRegister()) {
			player.sendRichMessage("<yellow>" + decision.message() + "</yellow>");
			return;
		}
		player.sendRichMessage("<yellow>" + decision.message() + "</yellow>");
	}

	@Subscribe
	public void onServerConnected(ServerConnectedEvent event) {
		if (!isReady()) {
			return;
		}

		flow("ServerConnected player=" + event.getPlayer().getUsername() + " server=" + event.getServer().getServerInfo().getName());
		UUID playerUuid = event.getPlayer().getUniqueId();
		boolean authStateSent = syncAuthState(event.getPlayer(), event.getServer());
		String pendingMessage = pendingBackendAuthMessages.get(playerUuid);
		boolean authResultSent = true;
		if (pendingMessage != null) {
			authResultSent = sendAuthenticatedBackendNotice(event.getPlayer(), event.getServer(), pendingMessage);
			if (authResultSent) {
				pendingBackendAuthMessages.remove(playerUuid, pendingMessage);
			}
		}

		if (!authStateSent || !authResultSent) {
			scheduleBackendSyncRetry(playerUuid, 1);
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		if (!isReady()) {
			return;
		}

		flow("Disconnect player=" + event.getPlayer().getUsername() + " remote=" + event.getPlayer().getRemoteAddress());
		verifiedOnlineSessions.remove(event.getPlayer().getRemoteAddress());
		pendingBackendAuthMessages.remove(event.getPlayer().getUniqueId());
		backendSyncRetryInFlight.remove(event.getPlayer().getUniqueId());
		authService.onDisconnect(event.getPlayer().getUniqueId());
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		initAudit("Đang tắt LunaAuth Velocity và hủy đăng ký channels.");
		if (pluginMessagingBus != null) {
			pluginMessagingBus.unregisterIncoming(AuthChannels.COMMAND_REQUEST);
			pluginMessagingBus.unregisterOutgoing(AuthChannels.AUTH_STATE);
			pluginMessagingBus.unregisterOutgoing(AuthChannels.COMMAND_RESPONSE);
			pluginMessagingBus.unregisterOutgoing(AuthChannels.ADMIN_REQUEST);
		}
	}

	private void registerCommands() {
		CommandManager manager = proxyServer.getCommandManager();
		manager.register(manager.metaBuilder("logout").aliases("lo").build(), new LogoutCommand(authService, this::syncAuthState));
		CommandMeta authMeta = manager.metaBuilder("auth").aliases("lauth").build();
		manager.register(authMeta, new AuthAdminCommand(proxyServer, authService, this::syncAuthState, this::sendSetSpawnRequest, premiumUuidEnabled, uuidOverrideMap.size()));
	}

	private void sendSetSpawnRequest(Player target, String actorName) {
		if (!isReady()) {
			warnNotReady("ADMIN_REQUEST:set_spawn", target.getUsername());
			return;
		}

		target.getCurrentServer().ifPresent(connection -> pluginMessagingBus.send(connection, AuthChannels.ADMIN_REQUEST, writer -> {
			writer.writeUtf("set_spawn");
			writer.writeUuid(target.getUniqueId());
			writer.writeUtf(actorName);
		}));
		flow("TX admin_request set_spawn target=" + target.getUsername() + " actor=" + actorName);
	}

	private void syncAuthState(Player player) {
		if (!isReady()) {
			return;
		}

		boolean authenticated = authService.isAuthenticated(player.getUniqueId());
		flow("syncAuthState player=" + player.getUsername() + " authenticated=" + authenticated);
		if (authenticated) {
			player.sendActionBar(Component.text("Bạn đã xác thực."));
		}
		player.getCurrentServer().ifPresent(connection -> sendAuthState(connection, player, authenticated));
	}

	private boolean syncAuthState(Player player, RegisteredServer server) {
		if (!isReady()) {
			return false;
		}

		boolean authenticated = authService.isAuthenticated(player.getUniqueId());
		flow("syncAuthState player=" + player.getUsername() + " authenticated=" + authenticated + " via=ServerConnected");
		if (authenticated) {
			player.sendActionBar(Component.text("Bạn đã xác thực."));
		}
		return sendAuthState(server, player, authenticated);
	}

	private boolean sendAuthState(ServerConnection connection, Player player, boolean authenticated) {
		if (!isReady()) {
			return false;
		}
		boolean needsRegister = needsRegister(player.getUniqueId());

		boolean sent = pluginMessagingBus.send(connection, AuthChannels.AUTH_STATE, writer -> {
			writer.writeUtf("state");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(authenticated);
			writer.writeBoolean(needsRegister);
			writer.writeUtf(player.getUsername());
		});
		flow("TX auth_state player=" + player.getUsername() + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " server=" + connection.getServerInfo().getName() + " sent=" + sent);
		return sent;
	}

	private boolean sendAuthState(RegisteredServer server, Player player, boolean authenticated) {
		if (!isReady()) {
			return false;
		}
		boolean needsRegister = needsRegister(player.getUniqueId());

		boolean sent = pluginMessagingBus.send(server, AuthChannels.AUTH_STATE, writer -> {
			writer.writeUtf("state");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(authenticated);
			writer.writeBoolean(needsRegister);
			writer.writeUtf(player.getUsername());
		});
		flow("TX auth_state player=" + player.getUsername() + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " server=" + server.getServerInfo().getName() + " sent=" + sent);
		return sent;
	}

	private void sendCommandResponse(Player player, boolean success, String message) {
		if (!isReady()) {
			return;
		}
		boolean authenticated = authService.isAuthenticated(player.getUniqueId());
		boolean needsRegister = needsRegister(player.getUniqueId());

		player.getCurrentServer().ifPresent(connection -> {
			boolean sent = pluginMessagingBus.send(connection, AuthChannels.COMMAND_RESPONSE, writer -> {
			writer.writeUtf("auth_result");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(success);
			writer.writeBoolean(authenticated);
			writer.writeBoolean(needsRegister);
			writer.writeUtf(message);
			});
			flow("TX command_response player=" + player.getUsername() + " success=" + success + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " server=" + connection.getServerInfo().getName() + " sent=" + sent);
		});
	}

	private boolean sendAuthenticatedBackendNotice(Player player, String message) {
		if (!isReady()) {
			return false;
		}
		Optional<ServerConnection> connectionOptional = player.getCurrentServer();
		if (connectionOptional.isEmpty()) {
			return false;
		}
		return sendAuthenticatedBackendNotice(player, connectionOptional.get(), message);
 	}

	private boolean sendAuthenticatedBackendNotice(Player player, ServerConnection connection, String message) {
		boolean sent = pluginMessagingBus.send(connection, AuthChannels.COMMAND_RESPONSE, writer -> {
			writer.writeUtf("auth_result");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(true);
			writer.writeBoolean(true);
			writer.writeBoolean(false);
			writer.writeUtf(message);
		});
		flow("TX command_response(auto-auth) player=" + player.getUsername() + " message=" + message + " server=" + connection.getServerInfo().getName() + " sent=" + sent);
		return sent;
	}

	private boolean sendAuthenticatedBackendNotice(Player player, RegisteredServer server, String message) {
		boolean sent = pluginMessagingBus.send(server, AuthChannels.COMMAND_RESPONSE, writer -> {
			writer.writeUtf("auth_result");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(true);
			writer.writeBoolean(true);
			writer.writeBoolean(false);
			writer.writeUtf(message);
		});
		flow("TX command_response(auto-auth) player=" + player.getUsername() + " message=" + message + " server=" + server.getServerInfo().getName() + " sent=" + sent);
		return sent;
	}

	private void scheduleBackendSyncRetry(UUID playerUuid, int nextAttempt) {
		if (!backendSyncRetryInFlight.add(playerUuid)) {
			return;
		}

		proxyServer.getScheduler().buildTask(this, () -> runBackendSyncRetry(playerUuid, nextAttempt))
			.delay(BACKEND_SYNC_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
			.schedule();
	}

	private void runBackendSyncRetry(UUID playerUuid, int attempt) {
		if (!isReady()) {
			backendSyncRetryInFlight.remove(playerUuid);
			return;
		}

		Player player = proxyServer.getPlayer(playerUuid).orElse(null);
		if (player == null) {
			pendingBackendAuthMessages.remove(playerUuid);
			backendSyncRetryInFlight.remove(playerUuid);
			return;
		}

		Optional<ServerConnection> connectionOptional = player.getCurrentServer();
		if (connectionOptional.isEmpty()) {
			if (attempt >= BACKEND_SYNC_RETRY_MAX_ATTEMPTS) {
				flow("Retry backend sync give up player=" + player.getUsername() + " reason=NO_SERVER_CONNECTION attempts=" + attempt);
				backendSyncRetryInFlight.remove(playerUuid);
				return;
			}
			scheduleNextBackendSyncRetry(playerUuid, attempt + 1);
			return;
		}

		ServerConnection connection = connectionOptional.get();
		boolean authenticated = authService.isAuthenticated(playerUuid);
		boolean stateSent = sendAuthState(connection, player, authenticated);

		String pendingMessage = pendingBackendAuthMessages.get(playerUuid);
		boolean authResultSent = true;
		if (authenticated && pendingMessage != null) {
			authResultSent = sendAuthenticatedBackendNotice(player, connection, pendingMessage);
			if (authResultSent) {
				pendingBackendAuthMessages.remove(playerUuid, pendingMessage);
			}
		}

		if (stateSent && authResultSent) {
			flow("Retry backend sync success player=" + player.getUsername() + " attempt=" + attempt);
			backendSyncRetryInFlight.remove(playerUuid);
			return;
		}

		if (attempt >= BACKEND_SYNC_RETRY_MAX_ATTEMPTS) {
			flow("Retry backend sync give up player=" + player.getUsername() + " attempt=" + attempt + " stateSent=" + stateSent + " authResultSent=" + authResultSent);
			backendSyncRetryInFlight.remove(playerUuid);
			return;
		}

		scheduleNextBackendSyncRetry(playerUuid, attempt + 1);
	}

	private void scheduleNextBackendSyncRetry(UUID playerUuid, int nextAttempt) {
		proxyServer.getScheduler().buildTask(this, () -> runBackendSyncRetry(playerUuid, nextAttempt))
			.delay(BACKEND_SYNC_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
			.schedule();
	}

	private boolean needsRegister(UUID playerUuid) {
		return authService.account(playerUuid)
			.map(account -> !account.hasPassword())
			.orElse(true);
	}

	private AuthService.QuickAuthTrustDecision quickAuthDecision(Player player) {
		if (!isReady()) {
			return AuthService.QuickAuthTrustDecision.denied("AUTH_SERVICE_NOT_READY");
		}

		if (proxyServer.getConfiguration().isOnlineMode()) {
			flow("Quick-login trusted vì proxy global online-mode=true cho player=" + player.getUsername());
			return AuthService.QuickAuthTrustDecision.trusted("QUICK_AUTH_ONLINE_UUID");
		}

		if (mixedModeQuickLoginEnabled) {
			boolean verified = verifiedOnlineSessions.remove(player.getRemoteAddress());
			if (!verified) {
				flow("Không có verified online session cho player=" + player.getUsername() + ", reason=TRUST_UNAVAILABLE, giữ flow /login bình thường.");
				return AuthService.QuickAuthTrustDecision.denied("TRUST_UNAVAILABLE");
			} else {
				flow("Quick-login trusted theo mixed-mode verified-session cho player=" + player.getUsername());
				return AuthService.QuickAuthTrustDecision.trusted("QUICK_AUTH_ONLINE_UUID");
			}
		}

		flow("Quick-login fail-closed cho player=" + player.getUsername() + " vì proxy offline-mode và mixed-mode=false, reason=FORWARDING_INVALID");
		return AuthService.QuickAuthTrustDecision.denied("FORWARDING_INVALID");
	}

	private String ipAddress(Player player) {
		if (player.getRemoteAddress() instanceof InetSocketAddress socketAddress) {
			return socketAddress.getAddress().getHostAddress();
		}
		return "0.0.0.0";
	}

	private boolean isBackendHandshakeReady() {
		if (!backendHandshakeRequired) {
			return true;
		}
		if (backendStatusView == null) {
			return false;
		}

		Map<String, BackendServerStatus> snapshot = backendStatusView.snapshot();
		boolean ready;
		if (requiredBackendNames.isEmpty()) {
			ready = snapshot.values().stream().anyMatch(BackendServerStatus::online);
		} else {
			ready = requiredBackendNames.stream().allMatch(name -> {
				BackendServerStatus status = snapshot.get(name);
				return status != null && status.online();
			});
		}

		if (ready != lastBackendReady) {
			if (ready) {
				logger.success("Auth backend handshake sẵn sàng. Cho phép kết nối mới.");
			} else {
				logger.warn("Auth backend handshake chưa sẵn sàng. Tạm khóa kết nối mới.");
			}
			lastBackendReady = ready;
		}

		return ready;
	}

	private Set<String> parseRequiredBackends(Object rawValue) {
		if (!(rawValue instanceof Iterable<?> iterable)) {
			return Set.of();
		}

		Set<String> out = new HashSet<>();
		for (Object item : iterable) {
			if (item == null) {
				continue;
			}
			String value = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
			if (!value.isBlank()) {
				out.add(value);
			}
		}
		return Set.copyOf(out);
	}

	private Map<UUID, UUID> parseUuidOverrideMap(Map<String, Object> rawMap) {
		Map<UUID, UUID> parsed = new HashMap<>();
		for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
			String sourceRaw = entry.getKey();
			Object targetRaw = entry.getValue();
			if (!(targetRaw instanceof String targetString)) {
				initWarn("Bỏ qua uuid-override-map entry không hợp lệ: from=" + sourceRaw + " (giá trị không phải chuỗi UUID)");
				continue;
			}

			try {
				UUID from = UUID.fromString(sourceRaw.trim());
				UUID to = UUID.fromString(targetString.trim());
				parsed.put(from, to);
			} catch (IllegalArgumentException exception) {
				initWarn("Bỏ qua uuid-override-map entry không hợp lệ: from=" + sourceRaw + " to=" + targetString);
			}
		}

		if (!parsed.isEmpty()) {
			initAudit("Đã nạp " + parsed.size() + " UUID override rule(s) từ config.");
		}

		return Map.copyOf(parsed);
	}

	private void initWarn(String message) {
		if (logger != null) {
			logger.warn(message);
			return;
		}
		Logger.getLogger("LunaAuthVelocity").warning(message);
	}

	private void initAudit(String message) {
		if (logger != null) {
			logger.audit(message);
			return;
		}
		Logger.getLogger("LunaAuthVelocity").info(message);
	}

	private void ensureDefaults() {
		Path config = dataDirectory.resolve("config.yml");
		LunaYamlConfig.ensureFile(config, () -> getClass().getClassLoader().getResourceAsStream("config.yml"));
		migrateConfig(config);
	}

	private void migrateConfig(Path configPath) {
		try (InputStream defaultsStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
			if (defaultsStream == null) {
				return;
			}

			Map<String, Object> defaults = loadYamlMap(defaultsStream);
			if (defaults.isEmpty()) {
				return;
			}

			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(configPath));
			if (mergeMissing(current, defaults)) {
				dumpYamlMap(configPath, current);
				initAudit("Đã migrate config.yml và bổ sung các khóa cấu hình còn thiếu.");
			}
		} catch (Exception exception) {
			initWarn("Không thể migrate config.yml tự động: " + exception.getMessage());
		}
	}

	private Map<String, Object> loadYamlMap(InputStream stream) throws Exception {
		Class<?> yamlClass = Class.forName("org.yaml.snakeyaml.Yaml");
		Object yaml = yamlClass.getConstructor().newInstance();
		Object loaded;
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			loaded = yamlClass.getMethod("load", Reader.class).invoke(yaml, reader);
		}

		if (!(loaded instanceof Map<?, ?> map)) {
			return Map.of();
		}

		return normalizeMap(map);
	}

	private Map<String, Object> normalizeMap(Map<?, ?> raw) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			normalized.put(String.valueOf(entry.getKey()), normalizeNode(entry.getValue()));
		}
		return normalized;
	}

	private Object normalizeNode(Object value) {
		if (value instanceof Map<?, ?> map) {
			return normalizeMap(map);
		}
		if (value instanceof List<?> list) {
			List<Object> copied = new ArrayList<>();
			for (Object item : list) {
				copied.add(normalizeNode(item));
			}
			return copied;
		}
		return value;
	}

	private boolean mergeMissing(Map<String, Object> target, Map<String, Object> defaults) {
		boolean changed = false;
		for (Map.Entry<String, Object> entry : defaults.entrySet()) {
			String key = entry.getKey();
			Object defaultValue = entry.getValue();
			if (!target.containsKey(key)) {
				target.put(key, deepCopy(defaultValue));
				changed = true;
				continue;
			}

			Object currentValue = target.get(key);
			if (currentValue instanceof Map<?, ?> currentMapRaw && defaultValue instanceof Map<?, ?> defaultMapRaw) {
				@SuppressWarnings("unchecked")
				Map<String, Object> currentMap = (Map<String, Object>) currentMapRaw;
				@SuppressWarnings("unchecked")
				Map<String, Object> defaultMap = (Map<String, Object>) defaultMapRaw;
				if (mergeMissing(currentMap, defaultMap)) {
					changed = true;
				}
			}
		}
		return changed;
	}

	private Object deepCopy(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copied = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				copied.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
			}
			return copied;
		}
		if (value instanceof List<?> list) {
			List<Object> copied = new ArrayList<>();
			for (Object item : list) {
				copied.add(deepCopy(item));
			}
			return copied;
		}
		return value;
	}

	private void dumpYamlMap(Path outputPath, Map<String, Object> data) throws Exception {
		Class<?> yamlClass = Class.forName("org.yaml.snakeyaml.Yaml");
		Object yaml = yamlClass.getConstructor().newInstance();
		Path parent = outputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
			yamlClass.getMethod("dump", Object.class, Writer.class).invoke(yaml, data, writer);
		}
	}

	private boolean isReady() {
		return initialized && authService != null && pluginMessagingBus != null;
	}

	private void warnNotReady(String eventName, String subject) {
		String suffix = (subject == null || subject.isBlank()) ? "" : " subject=" + subject;
		if (logger != null) {
			logger.warn("Bỏ qua " + eventName + " vì LunaAuth chưa khởi tạo xong." + suffix);
			return;
		}
		Logger.getLogger("LunaAuthVelocity").warning("Bỏ qua " + eventName + " vì LunaAuth chưa khởi tạo xong." + suffix);
	}

	private void flow(String message) {
		if (authFlowLogsEnabled) {
			logger.audit(message);
		}
	}
}
