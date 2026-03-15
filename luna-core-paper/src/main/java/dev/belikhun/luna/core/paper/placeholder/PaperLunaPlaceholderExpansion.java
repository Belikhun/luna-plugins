package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class PaperLunaPlaceholderExpansion extends PlaceholderExpansion {
	private final JavaPlugin plugin;
	private final BackendStatusView statusView;

	public PaperLunaPlaceholderExpansion(JavaPlugin plugin, BackendStatusView statusView) {
		this.plugin = plugin;
		this.statusView = statusView;
	}

	@Override
	public String getIdentifier() {
		return "luna";
	}

	@Override
	public String getAuthor() {
		return "Belikhun";
	}

	@Override
	public String getVersion() {
		return plugin.getPluginMeta().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public String onRequest(OfflinePlayer player, String params) {
		if (params == null || params.isBlank()) {
			return "";
		}

		String normalized = params.trim().toLowerCase(Locale.ROOT);
		if ("online_servers".equals(normalized)) {
			return Integer.toString(onlineServers());
		}
		if ("registered_servers".equals(normalized) || "total_servers".equals(normalized)) {
			return Integer.toString(registeredServers());
		}
		if ("total_players".equals(normalized)) {
			return Integer.toString(totalPlayers());
		}

		String server;
		if ((server = paramServer(normalized, "server_status_")) != null) {
			return serverStatus(server);
		}
		if ((server = paramServer(normalized, "server_online_")) != null) {
			return Integer.toString(serverOnline(server));
		}
		if ((server = paramServer(normalized, "server_max_")) != null) {
			return Integer.toString(serverMax(server));
		}
		if ((server = paramServer(normalized, "server_tps_")) != null) {
			return serverTps(server);
		}
		if ((server = paramServer(normalized, "server_version_")) != null) {
			return serverVersion(server);
		}
		if ((server = paramServer(normalized, "server_display_")) != null) {
			return serverDisplay(server);
		}
		if ((server = paramServer(normalized, "server_color_")) != null) {
			return serverColor(server);
		}
		if ((server = paramServer(normalized, "server_whitelist_")) != null) {
			return Boolean.toString(serverWhitelist(server));
		}

		return "";
	}

	private int registeredServers() {
		return statusView.snapshot().size();
	}

	private int onlineServers() {
		int count = 0;
		for (BackendServerStatus status : statusView.snapshot().values()) {
			if (status != null && status.online()) {
				count++;
			}
		}
		return count;
	}

	private int totalPlayers() {
		int total = 0;
		for (BackendServerStatus status : statusView.snapshot().values()) {
			if (status == null || status.stats() == null) {
				continue;
			}
			total += Math.max(0, status.stats().onlinePlayers());
		}
		return total;
	}

	private String serverStatus(String serverName) {
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

	private int serverOnline(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.onlinePlayers());
	}

	private int serverMax(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.maxPlayers());
	}

	private String serverTps(String serverName) {
		double tps = stats(serverName) == null ? 0D : stats(serverName).tps();
		return String.format(Locale.US, "%.2f", tps);
	}

	private String serverVersion(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null || stats.version() == null || stats.version().isBlank() ? "unknown" : stats.version();
	}

	private String serverDisplay(String serverName) {
		BackendServerStatus status = status(serverName);
		if (status == null) {
			return serverName;
		}

		String display = status.serverDisplay();
		if (display == null || display.isBlank()) {
			return status.serverName();
		}
		return display;
	}

	private String serverColor(String serverName) {
		BackendServerStatus status = status(serverName);
		if (status == null || status.serverAccentColor() == null || status.serverAccentColor().isBlank()) {
			return "#F1FF68";
		}
		return status.serverAccentColor();
	}

	private boolean serverWhitelist(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats != null && stats.whitelistEnabled();
	}

	private BackendHeartbeatStats stats(String serverName) {
		BackendServerStatus status = status(serverName);
		return status == null ? null : status.stats();
	}

	private BackendServerStatus status(String serverName) {
		return statusView.status(serverName).orElse(null);
	}

	private String paramServer(String params, String prefix) {
		if (!params.startsWith(prefix)) {
			return null;
		}

		String serverName = params.substring(prefix.length()).trim();
		if (serverName.isEmpty()) {
			return null;
		}
		return serverName.toLowerCase(Locale.ROOT);
	}
}
