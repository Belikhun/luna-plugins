package dev.belikhun.luna.auth.backend;

import dev.belikhun.luna.auth.backend.command.BackendAuthProxyCommand;
import dev.belikhun.luna.auth.backend.listener.AuthRestrictionListener;
import dev.belikhun.luna.auth.backend.messaging.AuthChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.auth.backend.service.BackendAuthStateRegistry;
import dev.belikhun.luna.auth.backend.service.BackendAuthSpawnService;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.paper.LunaCore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class LunaAuthBackendPlugin extends JavaPlugin {
	private BackendAuthStateRegistry stateRegistry;
	private AuthRestrictionListener restrictionListener;
	private BackendAuthSpawnService spawnService;
	private LunaLogger logger;
	private boolean authFlowLogsEnabled;
	private boolean modeSelectorGuiEnabled;

	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng. LunaAuthBackend sẽ tắt.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		this.logger = LunaLogger.forPlugin(this, true).scope("AuthBackend");

		saveDefaultConfig();
		migrateConfig();
		this.authFlowLogsEnabled = getConfig().getBoolean("logging.auth-flow", true);
		this.modeSelectorGuiEnabled = getConfig().getBoolean("auth.mode-selector-gui.enabled", true);
		this.stateRegistry = new BackendAuthStateRegistry();
		this.spawnService = new BackendAuthSpawnService(this);
		this.restrictionListener = new AuthRestrictionListener(
			this,
			stateRegistry,
			spawnService,
			new AuthRestrictionListener.PromptTemplate(
				getConfig().getString("prompt.login.bossbar", "<color:" + LunaPalette.WARNING_500 + "><b>⚠ Vui lòng đăng nhập để tiếp tục</b></color>"),
				getConfig().getString("prompt.login.actionbar", "<color:" + LunaPalette.WARNING_300 + ">Dùng <color:" + LunaPalette.NEUTRAL_50 + ">/login <mật_khẩu></color> để đăng nhập</color>"),
				getConfig().getString("prompt.login.chat", "<color:" + LunaPalette.INFO_500 + ">ℹ Tài khoản đã đăng ký. Dùng <color:" + LunaPalette.NEUTRAL_50 + ">/login <mật_khẩu></color> để tiếp tục.</color>")
			),
			new AuthRestrictionListener.PromptTemplate(
				getConfig().getString("prompt.register.bossbar", "<color:" + LunaPalette.WARNING_500 + "><b>⚠ Tài khoản chưa đăng ký</b></color>"),
				getConfig().getString("prompt.register.actionbar", "<color:" + LunaPalette.WARNING_300 + ">Dùng <color:" + LunaPalette.NEUTRAL_50 + ">/register <mật_khẩu> <nhập_lại></color> để tạo tài khoản</color>"),
				getConfig().getString("prompt.register.chat", "<color:" + LunaPalette.INFO_500 + ">ℹ Tài khoản chưa đăng ký. Dùng <color:" + LunaPalette.NEUTRAL_50 + ">/register <mật_khẩu> <nhập_lại></color> để tiếp tục.</color>")
			),
			new AuthRestrictionListener.PromptTemplate(
				getConfig().getString("prompt.pending.bossbar", "<color:" + LunaPalette.WARNING_500 + "><b>⏳ Đang tải trạng thái xác thực...</b></color>"),
				getConfig().getString("prompt.pending.actionbar", "<color:" + LunaPalette.WARNING_300 + ">Đang kiểm tra trạng thái tài khoản...</color>"),
				getConfig().getString("prompt.pending.chat", "<color:" + LunaPalette.INFO_500 + ">ℹ Đang kiểm tra trạng thái xác thực, vui lòng chờ một chút.</color>")
			),
			readAllowedCommands(),
			this::requestStateSync,
			this::sendProbePreference,
			modeSelectorGuiEnabled,
			logger.scope("Restriction"),
			authFlowLogsEnabled
		);
		getServer().getPluginManager().registerEvents(restrictionListener, this);
		restrictionListener.startPromptTask();
		flow("Plugin enable complete, authFlowLogs=" + authFlowLogsEnabled + " modeSelectorGuiEnabled=" + modeSelectorGuiEnabled + " allowedCommands=" + readAllowedCommands());
		LunaCore.services().pluginMessaging().registerOutgoing(AuthChannels.COMMAND_REQUEST);
		LunaCore.services().pluginMessaging().registerIncoming(AuthChannels.ADMIN_REQUEST, context -> {
			if (!(context.source() instanceof Player source)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"set_spawn".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			UUID targetUuid = reader.readUuid();
			String actorName = reader.readUtf();
			flow("RX admin_request action=" + action + " source=" + source.getName() + " sourceUuid=" + source.getUniqueId() + " targetUuid=" + targetUuid + " actor=" + actorName);
			if (!source.getUniqueId().equals(targetUuid)) {
				flow("Ignore admin_request set_spawn because sourceUuid!=targetUuid source=" + source.getUniqueId() + " target=" + targetUuid);
				return PluginMessageDispatchResult.HANDLED;
			}
			boolean updated = spawnService.setSpawn(source.getLocation(), actorName);
			if (updated) {
				source.sendRichMessage("<color:" + LunaPalette.SUCCESS_500 + ">✔ Điểm auth-spawn đã được cập nhật bởi " + actorName + ".</color>");
			} else {
				source.sendRichMessage("<color:" + LunaPalette.DANGER_500 + ">❌ Không thể cập nhật auth-spawn tại vị trí hiện tại.</color>");
			}
			return PluginMessageDispatchResult.HANDLED;
		});

		LunaCore.services().pluginMessaging().registerIncoming(AuthChannels.AUTH_STATE, context -> {
			if (!(context.source() instanceof Player source)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"state".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			UUID playerUuid = reader.readUuid();
			boolean authenticated = reader.readBoolean();
			boolean needsRegister = reader.readBoolean();
			boolean premiumNameCandidate = reader.readBoolean();
			boolean hasModePreference = reader.readBoolean();
			String username = reader.readUtf();
			flow("RX auth_state action=" + action + " source=" + source.getName() + " sourceUuid=" + source.getUniqueId()
				+ " payloadUuid=" + playerUuid + " authenticated=" + authenticated + " needsRegister=" + needsRegister + " premiumName=" + premiumNameCandidate + " hasModePreference=" + hasModePreference + " username=" + username);
			if (!source.getUniqueId().equals(playerUuid)) {
				flow("Ignore auth_state due to UUID mismatch source=" + source.getUniqueId() + " payload=" + playerUuid);
				return PluginMessageDispatchResult.HANDLED;
			}
			restrictionListener.updateModeSelectorEligibility(source, premiumNameCandidate, hasModePreference);
			BackendAuthStateRegistry.AuthState previous = stateRegistry.state(playerUuid);
			boolean wasAuthenticated = stateRegistry.isAuthenticated(playerUuid);

			if (authenticated) {
				stateRegistry.markAuthenticated(playerUuid);
				restrictionListener.hidePrompt(source);
				flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=AUTH_STATE");
				if (!wasAuthenticated) {
					sendAuthenticatedFeedback(source);
				}
			} else {
				stateRegistry.markUnauthenticated(playerUuid, needsRegister);
				flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=AUTH_STATE");
			}
			return PluginMessageDispatchResult.HANDLED;
		});

		LunaCore.services().pluginMessaging().registerIncoming(AuthChannels.COMMAND_RESPONSE, context -> {
			if (!(context.source() instanceof Player source)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"auth_result".equals(action) && !"auth_result_v2".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			boolean v2Payload = "auth_result_v2".equals(action);

			UUID playerUuid = reader.readUuid();
			boolean success = reader.readBoolean();
			boolean authenticated = reader.readBoolean();
			boolean needsRegister = reader.readBoolean();
			boolean premiumNameCandidate = reader.readBoolean();
			boolean hasModePreference = reader.readBoolean();
			String authMethod = v2Payload ? reader.readUtf() : "default";
			String message = reader.readUtf();
			flow("RX command_response action=" + action + " source=" + source.getName() + " sourceUuid=" + source.getUniqueId()
				+ " payloadUuid=" + playerUuid + " success=" + success + " authenticated=" + authenticated
				+ " needsRegister=" + needsRegister + " premiumName=" + premiumNameCandidate + " hasModePreference=" + hasModePreference + " authMethod=" + authMethod + " message=" + message);
			if (!source.getUniqueId().equals(playerUuid)) {
				flow("Ignore command_response due to UUID mismatch source=" + source.getUniqueId() + " payload=" + playerUuid);
				return PluginMessageDispatchResult.HANDLED;
			}
			restrictionListener.updateModeSelectorEligibility(source, premiumNameCandidate, hasModePreference);
			BackendAuthStateRegistry.AuthState previous = stateRegistry.state(playerUuid);
			boolean wasAuthenticated = stateRegistry.isAuthenticated(playerUuid);

			boolean shouldSendResultChat = !success || !authenticated;
			if (shouldSendResultChat) {
				source.sendRichMessage((success ? "<green>" : "<red>") + message + (success ? "</green>" : "</red>"));
			}
			if (authenticated) {
				stateRegistry.markAuthenticated(playerUuid);
				restrictionListener.hidePrompt(source);
				flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=COMMAND_RESPONSE");
				if (!wasAuthenticated) {
					sendAuthenticatedFeedback(source, authMethod);
				}
			} else {
				stateRegistry.markUnauthenticated(playerUuid, needsRegister);
				flow("StateTransition uuid=" + playerUuid + " from=" + previous + " to=" + stateRegistry.state(playerUuid) + " reason=COMMAND_RESPONSE");
			}
			return PluginMessageDispatchResult.HANDLED;
		});

		BackendAuthProxyCommand loginCommand = new BackendAuthProxyCommand("login", LunaCore.services().pluginMessaging());
		BackendAuthProxyCommand registerCommand = new BackendAuthProxyCommand("register", LunaCore.services().pluginMessaging());

		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			commands.registrar().register("login", loginCommand);
			commands.registrar().register("l", loginCommand);
			commands.registrar().register("register", registerCommand);
			commands.registrar().register("reg", registerCommand);
		});
		logger.success("LunaAuthBackend đã khởi động.");
	}

	@Override
	public void onDisable() {
		if (getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			LunaCore.services().pluginMessaging().unregisterOutgoing(AuthChannels.COMMAND_REQUEST);
			LunaCore.services().pluginMessaging().unregisterIncoming(AuthChannels.AUTH_STATE);
			LunaCore.services().pluginMessaging().unregisterIncoming(AuthChannels.COMMAND_RESPONSE);
			LunaCore.services().pluginMessaging().unregisterIncoming(AuthChannels.ADMIN_REQUEST);
		}
	}

	private Set<String> readAllowedCommands() {
		List<String> configured = getConfig().getStringList("allowedCommands");
		Set<String> allowed = new HashSet<>();
		if (configured.isEmpty()) {
			configured = List.of("login", "register", "l", "reg", "help");
		}
		for (String value : configured) {
			if (value == null || value.isBlank()) {
				continue;
			}
			allowed.add(value.toLowerCase(Locale.ROOT));
		}
		return allowed;
	}

	private void migrateConfig() {
		// Copy any newly added defaults from bundled config.yml into existing server configs.
		getConfig().options().copyDefaults(true);

		List<String> configured = new ArrayList<>(getConfig().getStringList("allowedCommands"));
		boolean commandListChanged = false;
		commandListChanged |= removeCommand(configured, "logout");
		commandListChanged |= removeCommand(configured, "lo");

		if (commandListChanged) {
			getConfig().set("allowedCommands", configured);
		}

		saveConfig();
	}

	private boolean removeCommand(List<String> configured, String command) {
		return configured.removeIf(value -> value != null && command.equalsIgnoreCase(value));
	}

	private void sendAuthenticatedFeedback(Player player) {
		sendAuthenticatedFeedback(player, "default");
	}

	private void sendAuthenticatedFeedback(Player player, String authMethod) {
		String normalizedMethod = normalizeAuthMethod(authMethod);
		String methodBasePath = "prompt.authenticated.by-method." + normalizedMethod;
		String chat = getConfig().getString(methodBasePath + ".chat");
		if (chat == null || chat.isBlank()) {
			chat = getConfig().getString("prompt.authenticated.chat", "<green>✔ Bạn đã xác thực thành công.</green>");
		}
		String actionbar = getConfig().getString(methodBasePath + ".actionbar");
		if (actionbar == null || actionbar.isBlank()) {
			actionbar = getConfig().getString("prompt.authenticated.actionbar", "<green>✔ Đã xác thực</green>");
		}
		flow("SendAuthenticatedFeedback player=" + player.getName() + " uuid=" + player.getUniqueId() + " authMethod=" + normalizedMethod);
		player.sendRichMessage(chat);
		player.sendActionBar(MiniMessage.miniMessage().deserialize(actionbar));
	}

	private String normalizeAuthMethod(String authMethod) {
		if (authMethod == null || authMethod.isBlank()) {
			return "default";
		}
		String normalized = authMethod.trim().toLowerCase(Locale.ROOT);
		if ("quick-login".equals(normalized) || "quickauth".equals(normalized)) {
			return "quick_login";
		}
		if ("session-resume".equals(normalized) || "session_resume".equals(normalized)) {
			return "session_resume";
		}
		if ("login".equals(normalized) || "password-login".equals(normalized)) {
			return "password_login";
		}
		if ("register".equals(normalized) || "register-password".equals(normalized)) {
			return "register_password";
		}
		return normalized;
	}

	private void requestStateSync(Player player) {
		LunaCore.services().pluginMessaging().send(player, AuthChannels.COMMAND_REQUEST, writer -> {
			writer.writeUtf("sync_state");
			writer.writeUuid(player.getUniqueId());
			writer.writeUtf(player.getName());
		});
		flow("TX command_request action=sync_state player=" + player.getName() + " uuid=" + player.getUniqueId() + " at=" + Instant.now());
	}

	private void sendProbePreference(Player player, String mode) {
		LunaCore.services().pluginMessaging().send(player, AuthChannels.COMMAND_REQUEST, writer -> {
			writer.writeUtf("set_probe_preference");
			writer.writeUuid(player.getUniqueId());
			writer.writeUtf(player.getName());
			writer.writeUtf(mode);
		});
		flow("TX command_request action=set_probe_preference player=" + player.getName() + " uuid=" + player.getUniqueId() + " mode=" + mode + " at=" + Instant.now());
	}

	private void flow(String message) {
		if (!authFlowLogsEnabled) {
			return;
		}
		logger.audit(message);
	}
}
