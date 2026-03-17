package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
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
	private static final int REFRESH_INTERVAL = 500;

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
		registerServer(manager, "%lunav-online-servers%", () -> Integer.toString(values.onlineServers()));
		registerServer(manager, "%lunav-registered-servers%", () -> Integer.toString(values.registeredServers()));
		registerServer(manager, "%lunav-total-servers%", () -> Integer.toString(values.registeredServers()));
		registerServer(manager, "%lunav-total-players%", () -> Integer.toString(values.totalPlayers()));
		registerPlayer(manager, "%lunav-player-name%", player -> {
			Player velocityPlayer = velocityPlayer(player);
			return velocityPlayer != null ? values.playerName(velocityPlayer) : values.playerName(player.getName());
		});
		registerPlayer(manager, "%lunav-player-group-name%", player -> values.playerGroupName(player.getUniqueId()));
		registerPlayer(manager, "%lunav-player-group-display%", player -> values.playerGroupDisplay(player.getUniqueId()));
		registerPlayer(manager, "%lunav-player-prefix%", player -> values.playerPrefix(player.getUniqueId()));
		registerPlayer(manager, "%lunav-player-suffix%", player -> values.playerSuffix(player.getUniqueId()));
		registerPlayer(manager, "%lunav-player-display%", player -> {
			Player velocityPlayer = velocityPlayer(player);
			return velocityPlayer != null ? values.playerDisplay(velocityPlayer) : values.playerDisplay(player.getUniqueId(), player.getName());
		});

		for (VelocityServerSelectorConfig.ServerDefinition definition : values.servers().values()) {
			String key = values.normalize(definition.backendName());
			if (key.isBlank()) {
				continue;
			}

			registerServer(manager, "%lunav-server-status-" + key + "%", () -> values.serverStatus(key));
			registerServer(manager, "%lunav-server-online-" + key + "%", () -> Integer.toString(values.serverOnline(key)));
			registerServer(manager, "%lunav-server-max-" + key + "%", () -> Integer.toString(values.serverMax(key)));
			registerServer(manager, "%lunav-server-tps-" + key + "%", () -> values.serverTps(key));
			registerServer(manager, "%lunav-server-version-" + key + "%", () -> values.serverVersion(key));
			registerServer(manager, "%lunav-server-display-" + key + "%", () -> values.serverDisplay(key));
			registerServer(manager, "%lunav-server-color-" + key + "%", () -> values.serverColor(key));
			registerServer(manager, "%lunav-server-whitelist-" + key + "%", () -> Boolean.toString(values.serverWhitelist(key)));
		}

		logger.success("Đã đăng ký TAB placeholders nhóm %lunav-*% cho Velocity.");
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
		try {
			manager.registerServerPlaceholder(identifier, REFRESH_INTERVAL, supplier);
		} catch (Throwable throwable) {
			logger.error("Không thể đăng ký TAB server placeholder " + identifier, throwable);
			throw throwable;
		}
		registeredPlaceholders.add(identifier);
	}

	private void registerPlayer(PlaceholderManager manager, String identifier, java.util.function.Function<TabPlayer, String> resolver) {
		try {
			manager.registerPlayerPlaceholder(identifier, REFRESH_INTERVAL, player -> {
				try {
					return resolver.apply(player);
				} catch (Throwable throwable) {
					logger.error("Lỗi khi resolve TAB player placeholder " + identifier + " cho " + player.getName(), throwable);
					throw throwable;
				}
			});
		} catch (Throwable throwable) {
			logger.error("Không thể đăng ký TAB player placeholder " + identifier, throwable);
			throw throwable;
		}
		registeredPlaceholders.add(identifier);
	}

	private Player velocityPlayer(TabPlayer player) {
		if (player == null) {
			return null;
		}

		Object platformPlayer = player.getPlayer();
		return platformPlayer instanceof Player velocityPlayer ? velocityPlayer : null;
	}
}
