package dev.belikhun.luna.auth.backend;

import dev.belikhun.luna.auth.backend.command.BackendAuthProxyCommand;
import dev.belikhun.luna.auth.backend.listener.AuthRestrictionListener;
import dev.belikhun.luna.auth.backend.messaging.AuthChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.auth.backend.service.BackendAuthStateRegistry;
import dev.belikhun.luna.auth.backend.service.BackendAuthSpawnService;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.paper.LunaCore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class LunaAuthBackendPlugin extends JavaPlugin {
	private BackendAuthStateRegistry stateRegistry;
	private AuthRestrictionListener restrictionListener;
	private BackendAuthSpawnService spawnService;

	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng. LunaAuthBackend sẽ tắt.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		saveDefaultConfig();
		this.stateRegistry = new BackendAuthStateRegistry();
		this.spawnService = new BackendAuthSpawnService(this);
		this.restrictionListener = new AuthRestrictionListener(
			this,
			stateRegistry,
			spawnService,
			getConfig().getString("prompt.bossbar", "<color:" + LunaPalette.WARNING_500 + "><b>⚠ Xác thực tài khoản để tiếp tục</b></color>"),
			getConfig().getString("prompt.actionbar", "<color:" + LunaPalette.WARNING_300 + ">Vui lòng dùng <color:" + LunaPalette.NEUTRAL_50 + ">/login</color> hoặc <color:" + LunaPalette.NEUTRAL_50 + ">/register</color></color>"),
			getConfig().getString("prompt.chat", "<color:" + LunaPalette.INFO_500 + ">ℹ Vui lòng dùng <color:" + LunaPalette.NEUTRAL_50 + ">/login</color> hoặc <color:" + LunaPalette.NEUTRAL_50 + ">/register</color> để tiếp tục.</color>"),
			readAllowedCommands()
		);
		getServer().getPluginManager().registerEvents(restrictionListener, this);
		restrictionListener.startPromptTask();
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
			if (!source.getUniqueId().equals(targetUuid)) {
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
			reader.readUtf();
			if (!source.getUniqueId().equals(playerUuid)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			if (authenticated) {
				stateRegistry.markAuthenticated(playerUuid);
				restrictionListener.hidePrompt(source);
			} else {
				stateRegistry.markUnauthenticated(playerUuid);
			}
			return PluginMessageDispatchResult.HANDLED;
		});

		LunaCore.services().pluginMessaging().registerIncoming(AuthChannels.COMMAND_RESPONSE, context -> {
			if (!(context.source() instanceof Player source)) {
				return PluginMessageDispatchResult.HANDLED;
			}
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String action = reader.readUtf();
			if (!"auth_result".equals(action)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			UUID playerUuid = reader.readUuid();
			boolean success = reader.readBoolean();
			boolean authenticated = reader.readBoolean();
			String message = reader.readUtf();
			if (!source.getUniqueId().equals(playerUuid)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			source.sendRichMessage((success ? "<green>" : "<red>") + message + (success ? "</green>" : "</red>"));
			if (authenticated) {
				stateRegistry.markAuthenticated(playerUuid);
				restrictionListener.hidePrompt(source);
			} else {
				stateRegistry.markUnauthenticated(playerUuid);
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
		getLogger().info("LunaAuthBackend đã khởi động.");
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
}
