package dev.belikhun.luna.core.velocity.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.velocity.VelocityPlayerDisplayFormat;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.placeholder.PlaceholderManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class VelocityLunaTabPlaceholders {
	private static final int REFRESH_INTERVAL = -1;

	private final LunaLogger logger;
	private final VelocityLunaPlaceholderValues values;
	private final List<String> registeredPlaceholders = new ArrayList<>();

	public VelocityLunaTabPlaceholders(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityServerSelectorConfig selectorConfig,
		ServerDisplayResolver serverDisplayResolver,
		VelocityPlayerDisplayFormat playerDisplayFormat
	) {
		this.logger = logger.scope("TAB");
		this.values = new VelocityLunaPlaceholderValues(statusRegistry, selectorConfig, serverDisplayResolver, playerDisplayFormat);
	}

	public void register() {
		unregister();

		PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();
		registerServer(manager, "%luna_online_servers%", () -> Integer.toString(values.onlineServers()));
		registerServer(manager, "%luna_registered_servers%", () -> Integer.toString(values.registeredServers()));
		registerServer(manager, "%luna_total_servers%", () -> Integer.toString(values.registeredServers()));
		registerServer(manager, "%luna_total_players%", () -> Integer.toString(values.totalPlayers()));
		registerPlayer(manager, "%luna_player_name%", player -> values.playerName(player.getName()));
		registerPlayer(manager, "%luna_player_group_name%", player -> values.playerGroupName(player.getUniqueId()));
		registerPlayer(manager, "%luna_player_group_display%", player -> values.playerGroupDisplay(player.getUniqueId()));
		registerPlayer(manager, "%luna_player_prefix%", player -> values.playerPrefix(player.getUniqueId()));
		registerPlayer(manager, "%luna_player_suffix%", player -> values.playerSuffix(player.getUniqueId()));
		registerPlayer(manager, "%luna_player_display%", player -> values.playerDisplay(player.getUniqueId(), player.getName()));

		for (VelocityServerSelectorConfig.ServerDefinition definition : values.servers().values()) {
			String key = values.normalize(definition.backendName());
			if (key.isBlank()) {
				continue;
			}

			registerServer(manager, "%luna_server_status_" + key + "%", () -> values.serverStatus(key));
			registerServer(manager, "%luna_server_online_" + key + "%", () -> Integer.toString(values.serverOnline(key)));
			registerServer(manager, "%luna_server_max_" + key + "%", () -> Integer.toString(values.serverMax(key)));
			registerServer(manager, "%luna_server_tps_" + key + "%", () -> values.serverTps(key));
			registerServer(manager, "%luna_server_version_" + key + "%", () -> values.serverVersion(key));
			registerServer(manager, "%luna_server_display_" + key + "%", () -> values.serverDisplay(key));
			registerServer(manager, "%luna_server_color_" + key + "%", () -> values.serverColor(key));
			registerServer(manager, "%luna_server_whitelist_" + key + "%", () -> Boolean.toString(values.serverWhitelist(key)));
		}

		logger.success("Đã đăng ký TAB placeholders nhóm %luna_*% cho Velocity.");
	}

	public void unregister() {
		if (registeredPlaceholders.isEmpty()) {
			return;
		}

		try {
			PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();
			for (String identifier : registeredPlaceholders) {
				manager.unregisterPlaceholder(identifier);
			}
		} catch (Throwable ignored) {
		}

		registeredPlaceholders.clear();
	}

	private void registerServer(PlaceholderManager manager, String identifier, Supplier<String> supplier) {
		manager.registerServerPlaceholder(identifier, REFRESH_INTERVAL, supplier);
		registeredPlaceholders.add(identifier);
	}

	private void registerPlayer(PlaceholderManager manager, String identifier, java.util.function.Function<TabPlayer, String> resolver) {
		manager.registerPlayerPlaceholder(identifier, REFRESH_INTERVAL, player -> resolver.apply(player));
		registeredPlaceholders.add(identifier);
	}
}
