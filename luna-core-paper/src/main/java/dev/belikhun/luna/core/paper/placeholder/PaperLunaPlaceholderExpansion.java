package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Locale;
import java.util.function.IntFunction;

public final class PaperLunaPlaceholderExpansion extends PlaceholderExpansion {
	private static final int DEFAULT_BAR_WIDTH = 25;
	private static final int MIN_BAR_WIDTH = 1;
	private static final int MAX_BAR_WIDTH = 120;

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
		String value = resolveCurrent(player, normalized);
		return value == null ? "" : value;
	}

	private String resolveCurrent(OfflinePlayer player, String normalized) {
		String currentServer = currentServerName();
		String value = switch (normalized) {
			case "current_server" -> currentServer;
			case "status" -> statusText(currentServer);
			case "online" -> Integer.toString(onlinePlayers(currentServer));
			case "max" -> Integer.toString(maxPlayers(currentServer));
			case "tps" -> tps(currentServer);
			case "player_ping" -> Long.toString(playerPing(player));
			case "latency" -> latency(currentServer);
			case "uptime" -> uptime(currentServer);
			case "uptime_ms" -> Long.toString(uptimeMillis(currentServer));
			case "system_cpu" -> formatPercent(systemCpuPercent(currentServer));
			case "process_cpu" -> formatPercent(processCpuPercent());
			case "version" -> version(currentServer);
			case "display" -> displayName(currentServer);
			case "color" -> accentColor(currentServer);
			case "whitelist" -> Boolean.toString(whitelist(currentServer));
			default -> null;
		};
		if (value != null) {
			return value;
		}

		value = resolveCurrentBar(normalized, "tps_bar", width -> tpsBar(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "player_ping_bar", width -> playerPingBar(player, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "latency_bar", width -> latencyBar(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "system_cpu_bar", width -> systemCpuBar(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "process_cpu_bar", width -> processCpuBar(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "ram_bar", width -> ramBar(currentServer, width));
		if (value != null) {
			return value;
		}

		value = resolveCurrentBar(normalized, "tps_bar_only", width -> tpsBarOnly(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "player_ping_bar_only", width -> playerPingBarOnly(player, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "latency_bar_only", width -> latencyBarOnly(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "system_cpu_bar_only", width -> systemCpuBarOnly(currentServer, width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "process_cpu_bar_only", width -> processCpuBarOnly(currentServer, width));
		if (value != null) {
			return value;
		}
		return resolveCurrentBar(normalized, "ram_bar_only", width -> ramBarOnly(currentServer, width));
	}

	private String resolveCurrentBar(String key, String baseKey, IntFunction<String> renderer) {
		Integer width = parseCurrentWidth(key, baseKey);
		if (width == null) {
			return null;
		}
		return renderer.apply(width);
	}

	private Integer parseCurrentWidth(String key, String baseKey) {
		if (key.equals(baseKey)) {
			return DEFAULT_BAR_WIDTH;
		}
		String prefix = baseKey + "_";
		if (!key.startsWith(prefix)) {
			return null;
		}
		String widthRaw = key.substring(prefix.length()).trim();
		if (widthRaw.isEmpty()) {
			return DEFAULT_BAR_WIDTH;
		}
		Integer parsed = parseWidth(widthRaw);
		return parsed == null ? DEFAULT_BAR_WIDTH : parsed;
	}

	private String statusText(String serverName) {
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

	private int onlinePlayers(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.onlinePlayers());
	}

	private int maxPlayers(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.maxPlayers());
	}

	private String tps(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		double tps = stats == null ? 0D : stats.tps();
		return String.format(Locale.US, "%.2f", tps);
	}

	private String latency(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		long latency = stats == null ? 0L : Math.max(0L, stats.heartbeatLatencyMillis());
		return Long.toString(latency);
	}

	private String uptime(String serverName) {
		return Formatters.duration(Duration.ofMillis(uptimeMillis(serverName)));
	}

	private long uptimeMillis(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0L : Math.max(0L, stats.uptimeMillis());
	}

	private String tpsBar(String serverName, int width) {
		BackendHeartbeatStats stats = stats(serverName);
		double tps = stats == null ? 0D : Math.max(0D, stats.tps());
		return buildBar(LunaProgressBarPresets.tps("tps", tps), width);
	}

	private String tpsBarOnly(String serverName, int width) {
		BackendHeartbeatStats stats = stats(serverName);
		double tps = stats == null ? 0D : Math.max(0D, stats.tps());
		return buildBarOnly(LunaProgressBarPresets.tps("tps", tps), width);
	}

	private String playerPingBar(OfflinePlayer player, int width) {
		long ping = playerPing(player);
		return buildBar(LunaProgressBarPresets.latency("ping", ping), width);
	}

	private String playerPingBarOnly(OfflinePlayer player, int width) {
		long ping = playerPing(player);
		return buildBarOnly(LunaProgressBarPresets.latency("ping", ping), width);
	}

	private String latencyBar(String serverName, int width) {
		BackendHeartbeatStats stats = stats(serverName);
		double latency = stats == null ? 0D : Math.max(0D, stats.heartbeatLatencyMillis());
		return buildBar(LunaProgressBarPresets.latency("latency", latency), width);
	}

	private String latencyBarOnly(String serverName, int width) {
		BackendHeartbeatStats stats = stats(serverName);
		double latency = stats == null ? 0D : Math.max(0D, stats.heartbeatLatencyMillis());
		return buildBarOnly(LunaProgressBarPresets.latency("latency", latency), width);
	}

	private String systemCpuBar(String serverName, int width) {
		double cpu = systemCpuPercent(serverName);
		return buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", cpu), width);
	}

	private String systemCpuBarOnly(String serverName, int width) {
		double cpu = systemCpuPercent(serverName);
		return buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", cpu), width);
	}

	private String processCpuBar(String serverName, int width) {
		double cpu = processCpuPercent(serverName);
		return buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", cpu), width);
	}

	private String processCpuBarOnly(String serverName, int width) {
		double cpu = processCpuPercent(serverName);
		return buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", cpu), width);
	}

	private String ramBar(String serverName, int width) {
		BackendHeartbeatStats stats = stats(serverName);
		long used = stats == null ? 0L : Math.max(0L, stats.ramUsedBytes());
		long max = stats == null ? 0L : Math.max(0L, stats.ramMaxBytes());
		return buildBar(LunaProgressBarPresets.ram("ram", used, max), width);
	}

	private String ramBarOnly(String serverName, int width) {
		BackendHeartbeatStats stats = stats(serverName);
		long used = stats == null ? 0L : Math.max(0L, stats.ramUsedBytes());
		long max = stats == null ? 0L : Math.max(0L, stats.ramMaxBytes());
		return buildBarOnly(LunaProgressBarPresets.ram("ram", used, max), width);
	}

	private String version(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null || stats.version() == null || stats.version().isBlank() ? "unknown" : stats.version();
	}

	private String displayName(String serverName) {
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

	private String accentColor(String serverName) {
		BackendServerStatus status = status(serverName);
		if (status == null || status.serverAccentColor() == null || status.serverAccentColor().isBlank()) {
			return "#F1FF68";
		}
		return status.serverAccentColor();
	}

	private boolean whitelist(String serverName) {
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

	private String buildBar(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).render();
	}

	private String buildBarOnly(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).label("").value("").render();
	}

	private int clampWidth(int width) {
		return Math.max(MIN_BAR_WIDTH, Math.min(MAX_BAR_WIDTH, width));
	}

	private Integer parseWidth(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return clampWidth(Integer.parseInt(raw.trim()));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private long playerPing(OfflinePlayer offlinePlayer) {
		if (offlinePlayer == null || !offlinePlayer.isOnline()) {
			return 0L;
		}
		Player onlinePlayer = offlinePlayer.getPlayer();
		if (onlinePlayer == null) {
			return 0L;
		}
		return Math.max(0L, onlinePlayer.getPing());
	}

	private double systemCpuPercent(String serverName) {
		if (serverName.equals(currentServerName())) {
			double local = readSparkSystemCpuPercent();
			if (local >= 0D) {
				return local;
			}
		}
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0D : Math.max(0D, Math.min(100D, stats.systemCpuUsagePercent()));
	}

	private double processCpuPercent() {
		double cpu = readSparkProcessCpuPercent();
		if (cpu >= 0D) {
			return cpu;
		}
		return 0D;
	}

	private double processCpuPercent(String serverName) {
		if (serverName.equals(currentServerName())) {
			return processCpuPercent();
		}
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0D : Math.max(0D, Math.min(100D, stats.processCpuUsagePercent()));
	}

	private double readSparkSystemCpuPercent() {
		try {
			Spark spark = SparkProvider.get();
			double raw = spark.cpuSystem().poll(CpuUsage.MINUTES_1);
			return normalizeCpuPercent(raw);
		} catch (Throwable ignored) {
		}
		return -1D;
	}

	private double readSparkProcessCpuPercent() {
		try {
			Spark spark = SparkProvider.get();
			double raw = spark.cpuProcess().poll(CpuUsage.MINUTES_1);
			return normalizeCpuPercent(raw);
		} catch (Throwable ignored) {
		}
		return -1D;
	}

	private double normalizeCpuPercent(double raw) {
		if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0D) {
			return -1D;
		}
		double percent = raw <= 1D ? (raw * 100D) : raw;
		return Math.max(0D, Math.min(100D, percent));
	}

	private String formatPercent(double value) {
		double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0D : Math.max(0D, Math.min(100D, value));
		return String.format(Locale.US, "%.1f", safe);
	}

	private String currentServerName() {
		String configured = plugin.getConfig().getString("heartbeat.serverName", "");
		if (configured != null && !configured.isBlank()) {
			return configured.trim().toLowerCase(Locale.ROOT);
		}
		String host = plugin.getServer().getIp();
		if (host == null || host.isBlank()) {
			host = "127.0.0.1";
		}
		return (host + ":" + Bukkit.getPort()).toLowerCase(Locale.ROOT);
	}
}
