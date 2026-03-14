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
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.belikhun.luna.auth.command.AuthAdminCommand;
import dev.belikhun.luna.auth.command.LoginCommand;
import dev.belikhun.luna.auth.command.RegisterCommand;
import dev.belikhun.luna.auth.messaging.AuthChannels;
import dev.belikhun.luna.auth.service.AuthRepository;
import dev.belikhun.luna.auth.service.AuthService;
import dev.belikhun.luna.auth.service.MojangPremiumCheckService;
import dev.belikhun.luna.auth.service.Pbkdf2PasswordHasher;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Plugin(
	id = "lunaauth",
	name = "LunaAuth",
	version = BuildConstants.VERSION,
	description = "Luna authentication authority for Velocity",
	authors = {"Belikhun"}
)
public final class LunaAuthVelocityPlugin {
	private final ProxyServer proxyServer;
	private final Path dataDirectory;
	private LunaLogger logger;
	private VelocityPluginMessagingBus pluginMessagingBus;
	private AuthService authService;
	private boolean mixedModeQuickLoginEnabled;
	private boolean authFlowLogsEnabled;
	private MojangPremiumCheckService mojangPremiumCheckService;
	private final Set<InetSocketAddress> verifiedOnlineSessions;

	@Inject
	public LunaAuthVelocityPlugin(ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.verifiedOnlineSessions = ConcurrentHashMap.newKeySet();
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		ensureDefaults();
		Path configPath = dataDirectory.resolve("config.yml");
		Map<String, Object> root = LunaYamlConfig.loadMap(configPath);
		Map<String, Object> logging = ConfigValues.map(root, "logging");
		Map<String, Object> auth = ConfigValues.map(root, "auth");
		Map<String, Object> hashing = ConfigValues.map(auth, "hashing");
		Map<String, Object> pbkdf2 = ConfigValues.map(hashing, "pbkdf2");
		Map<String, Object> quickLogin = ConfigValues.map(auth, "quick-login");
		Map<String, Object> mixedMode = ConfigValues.map(quickLogin, "mixed-mode");

		boolean ansi = ConfigValues.booleanValue(logging, "ansi", true);
		boolean debug = ConfigValues.booleanValue(logging, "debug", false);
		this.authFlowLogsEnabled = ConfigValues.booleanValue(logging, "auth-flow", true);
		this.mixedModeQuickLoginEnabled = ConfigValues.booleanValue(mixedMode, "enabled", true);
		int maxFailures = ConfigValues.intValue(auth, "max-failures", 5);
		long lockoutSeconds = Math.max(1, ConfigValues.intValue(auth, "lockout-seconds", 120));
		long sessionSeconds = Math.max(60, ConfigValues.intValue(auth, "session-after-disconnect-seconds", 900));
		int retentionDays = Math.max(1, ConfigValues.intValue(auth, "login-history-retention-days", 7));
		int hashIterations = Math.max(50_000, ConfigValues.intValue(pbkdf2, "iterations", 210_000));
		int hashSaltBytes = Math.max(16, ConfigValues.intValue(pbkdf2, "salt-bytes", 16));
		int hashKeyBits = Math.max(128, ConfigValues.intValue(pbkdf2, "key-bits", 256));
		long premiumCheckTimeoutMillis = Math.max(500L, ConfigValues.intValue(mixedMode, "premium-check-timeout-ms", 3000));
		long premiumNameCacheMinutes = Math.max(1L, ConfigValues.intValue(mixedMode, "premium-name-cache-minutes", 60));

		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaAuthVelocity"), ansi).withDebug(debug).scope("AuthVelocity");
		this.mojangPremiumCheckService = new MojangPremiumCheckService(logger, premiumCheckTimeoutMillis, premiumNameCacheMinutes, authFlowLogsEnabled);
		logger.audit("Khởi tạo LunaAuth Velocity: onlineMode=" + proxyServer.getConfiguration().isOnlineMode()
			+ ", mixedMode=" + mixedModeQuickLoginEnabled
			+ ", authFlowLogs=" + authFlowLogsEnabled
			+ ", hashIterations=" + hashIterations
			+ ", hashSaltBytes=" + hashSaltBytes
			+ ", hashKeyBits=" + hashKeyBits);
		Database database = LunaCoreVelocity.services().dependencyManager().resolve(Database.class);
		this.pluginMessagingBus = LunaCoreVelocity.services().pluginMessagingBus();
		this.pluginMessagingBus.registerIncoming(AuthChannels.COMMAND_REQUEST, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"login".equals(action) && !"register".equals(action)) {
				flow("Bỏ qua command_request action không hỗ trợ: " + action);
				return PluginMessageDispatchResult.HANDLED;
			}

			java.util.UUID playerUuid = reader.readUuid();
			String username = reader.readUtf();
			String password = reader.readUtf();
			String confirm = "login".equals(action) ? "" : reader.readUtf();
			flow("RX command_request action=" + action + " player=" + username + " uuid=" + playerUuid);
			Player player = proxyServer.getPlayer(playerUuid).orElse(null);
			if (player == null) {
				logger.warn("Nhận command_request nhưng player không còn online: uuid=" + playerUuid + " action=" + action);
				return PluginMessageDispatchResult.HANDLED;
			}

			AuthService.AuthResult result = "login".equals(action)
				? authService.login(playerUuid, username, ipAddress(player), password)
				: authService.register(playerUuid, username, ipAddress(player), password, confirm);
			flow("Xử lý command_request xong action=" + action + " player=" + username + " success=" + result.success());
			syncAuthState(player);
			sendCommandResponse(player, result.success(), result.message());
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
		logger.success("LunaAuth Velocity đã khởi động thành công.");
		if (mixedModeQuickLoginEnabled && !proxyServer.getConfiguration().isOnlineMode()) {
			flow("Mixed-mode quick-login bật trên proxy offline-mode: premium force-online, cracked theo offline flow.");
		}
	}

	@Subscribe
	public void onPreLogin(PreLoginEvent event) {
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
		if (!event.isOnlineMode()) {
			flow("GameProfileRequest username=" + event.getUsername() + " onlineMode=false, không đánh dấu verified session.");
			return;
		}

		verifiedOnlineSessions.add(event.getConnection().getRemoteAddress());
		flow("GameProfileRequest verified online session username=" + event.getUsername()
			+ " uuid=" + event.getGameProfile().getId()
			+ " remote=" + event.getConnection().getRemoteAddress());
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		Player player = event.getPlayer();
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
			player.sendRichMessage("<green>" + decision.message() + "</green>");
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
		flow("ServerConnected player=" + event.getPlayer().getUsername() + " server=" + event.getServer().getServerInfo().getName());
		syncAuthState(event.getPlayer());
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		flow("Disconnect player=" + event.getPlayer().getUsername() + " remote=" + event.getPlayer().getRemoteAddress());
		verifiedOnlineSessions.remove(event.getPlayer().getRemoteAddress());
		authService.onDisconnect(event.getPlayer().getUniqueId());
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		logger.audit("Đang tắt LunaAuth Velocity và hủy đăng ký channels.");
		if (pluginMessagingBus != null) {
			pluginMessagingBus.unregisterIncoming(AuthChannels.COMMAND_REQUEST);
			pluginMessagingBus.unregisterOutgoing(AuthChannels.AUTH_STATE);
			pluginMessagingBus.unregisterOutgoing(AuthChannels.COMMAND_RESPONSE);
			pluginMessagingBus.unregisterOutgoing(AuthChannels.ADMIN_REQUEST);
		}
	}

	private void registerCommands() {
		CommandManager manager = proxyServer.getCommandManager();
		manager.register(manager.metaBuilder("login").aliases("l").build(), new LoginCommand(authService, this::syncAuthState));
		manager.register(manager.metaBuilder("register").aliases("reg").build(), new RegisterCommand(authService, this::syncAuthState));
		CommandMeta authMeta = manager.metaBuilder("auth").aliases("lauth").build();
		manager.register(authMeta, new AuthAdminCommand(proxyServer, authService, this::syncAuthState, this::sendSetSpawnRequest));
	}

	private void sendSetSpawnRequest(Player target, String actorName) {
		target.getCurrentServer().ifPresent(connection -> pluginMessagingBus.send(connection, AuthChannels.ADMIN_REQUEST, writer -> {
			writer.writeUtf("set_spawn");
			writer.writeUuid(target.getUniqueId());
			writer.writeUtf(actorName);
		}));
		flow("TX admin_request set_spawn target=" + target.getUsername() + " actor=" + actorName);
	}

	private void syncAuthState(Player player) {
		boolean authenticated = authService.isAuthenticated(player.getUniqueId());
		flow("syncAuthState player=" + player.getUsername() + " authenticated=" + authenticated);
		if (authenticated) {
			player.sendActionBar(Component.text("Bạn đã xác thực."));
		}
		player.getCurrentServer().ifPresent(connection -> sendAuthState(connection, player, authenticated));
	}

	private void sendAuthState(ServerConnection connection, Player player, boolean authenticated) {
		flow("TX auth_state player=" + player.getUsername() + " authenticated=" + authenticated + " server=" + connection.getServerInfo().getName());
		pluginMessagingBus.send(connection, AuthChannels.AUTH_STATE, writer -> {
			writer.writeUtf("state");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(authenticated);
			writer.writeUtf(player.getUsername());
		});
	}

	private void sendCommandResponse(Player player, boolean success, String message) {
		flow("TX command_response player=" + player.getUsername() + " success=" + success + " authenticated=" + authService.isAuthenticated(player.getUniqueId()));
		player.getCurrentServer().ifPresent(connection -> pluginMessagingBus.send(connection, AuthChannels.COMMAND_RESPONSE, writer -> {
			writer.writeUtf("auth_result");
			writer.writeUuid(player.getUniqueId());
			writer.writeBoolean(success);
			writer.writeBoolean(authService.isAuthenticated(player.getUniqueId()));
			writer.writeUtf(message);
		}));
	}

	private AuthService.QuickAuthTrustDecision quickAuthDecision(Player player) {
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

	private void ensureDefaults() {
		Path config = dataDirectory.resolve("config.yml");
		LunaYamlConfig.ensureFile(config, () -> getClass().getClassLoader().getResourceAsStream("config.yml"));
	}

	private void flow(String message) {
		if (authFlowLogsEnabled) {
			logger.audit(message);
		}
	}
}
