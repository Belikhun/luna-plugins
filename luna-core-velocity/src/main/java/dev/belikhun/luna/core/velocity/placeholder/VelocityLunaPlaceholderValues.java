package dev.belikhun.luna.core.velocity.placeholder;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.velocity.VelocityPlayerDisplayFormat;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;

import com.velocitypowered.api.proxy.Player;

import java.util.UUID;
import java.util.Locale;
import java.util.Map;

final class VelocityLunaPlaceholderValues {
	static final String DEFAULT_COLOR = "#F1FF68";

	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityServerSelectorConfig selectorConfig;
	private final ServerDisplayResolver serverDisplayResolver;
	private final VelocityPlayerDisplayFormat playerDisplayFormat;

	VelocityLunaPlaceholderValues(
		VelocityBackendStatusRegistry statusRegistry,
		VelocityServerSelectorConfig selectorConfig,
		ServerDisplayResolver serverDisplayResolver,
		VelocityPlayerDisplayFormat playerDisplayFormat
	) {
		this.statusRegistry = statusRegistry;
		this.selectorConfig = selectorConfig;
		this.serverDisplayResolver = serverDisplayResolver;
		this.playerDisplayFormat = playerDisplayFormat;
	}

	Map<String, VelocityServerSelectorConfig.ServerDefinition> servers() {
		return selectorConfig.servers();
	}

	String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	int registeredServers() {
		return selectorConfig.servers().size();
	}

	int onlineServers() {
		int count = 0;
		Map<String, BackendServerStatus> snapshot = statusRegistry.snapshot();
		for (BackendServerStatus status : snapshot.values()) {
			if (status != null && status.online()) {
				count++;
			}
		}
		return count;
	}

	int totalPlayers() {
		int total = 0;
		Map<String, BackendServerStatus> snapshot = statusRegistry.snapshot();
		for (BackendServerStatus status : snapshot.values()) {
			if (status == null || status.stats() == null) {
				continue;
			}
			total += Math.max(0, status.stats().onlinePlayers());
		}
		return total;
	}

	String serverStatus(String serverName) {
		BackendServerStatus status = status(serverName);
		if (status == null || !status.online()) {
			return "OFFLINE";
		}

		BackendHeartbeatStats stats = status.stats();
		if (stats != null && stats.whitelistEnabled()) {
			return "MAINT";
		}

		return "ONLINE";
	}

	int serverOnline(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.onlinePlayers());
	}

	int serverMax(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.maxPlayers());
	}

	String serverTps(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		double value = stats == null ? 0D : stats.tps();
		return String.format(Locale.US, "%.2f", value);
	}

	String serverVersion(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null || stats.version() == null || stats.version().isBlank() ? "unknown" : stats.version();
	}

	String serverDisplay(String serverName) {
		String display = serverDisplayResolver.serverDisplay(serverName);
		if (display == null || display.isBlank()) {
			return serverName;
		}
		return display;
	}

	String serverColor(String serverName) {
		String color = serverDisplayResolver.serverColor(serverName);
		if (color == null || color.isBlank()) {
			return DEFAULT_COLOR;
		}
		return color;
	}

	boolean serverWhitelist(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats != null && stats.whitelistEnabled();
	}

	String playerName(Player player) {
		return playerDisplayFormat.playerName(player);
	}

	String playerName(String playerName) {
		return playerDisplayFormat.playerName(playerName);
	}

	String playerGroupName(Player player) {
		return playerDisplayFormat.playerGroupName(player);
	}

	String playerGroupName(UUID playerId) {
		return playerDisplayFormat.playerGroupName(playerId);
	}

	String playerGroupDisplay(Player player) {
		return playerDisplayFormat.playerGroupDisplay(player);
	}

	String playerGroupDisplay(UUID playerId) {
		return playerDisplayFormat.playerGroupDisplay(playerId);
	}

	String playerPrefix(Player player) {
		return playerDisplayFormat.playerPrefix(player);
	}

	String playerPrefix(UUID playerId) {
		return playerDisplayFormat.playerPrefix(playerId);
	}

	String playerSuffix(Player player) {
		return playerDisplayFormat.playerSuffix(player);
	}

	String playerSuffix(UUID playerId) {
		return playerDisplayFormat.playerSuffix(playerId);
	}

	String playerDisplay(Player player) {
		return playerDisplayFormat.format(player);
	}

	String playerDisplay(UUID playerId, String playerName) {
		return playerDisplayFormat.format(playerId, playerName);
	}

	private BackendHeartbeatStats stats(String serverName) {
		BackendServerStatus status = status(serverName);
		return status == null ? null : status.stats();
	}

	private BackendServerStatus status(String serverName) {
		return statusRegistry.status(serverName).orElse(null);
	}
}
