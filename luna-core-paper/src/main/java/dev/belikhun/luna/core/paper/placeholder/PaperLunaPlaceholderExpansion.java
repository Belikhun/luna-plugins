package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
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
		String value = switch (normalized) {
			case "current_server" -> currentServerName();
			case "status" -> statusText();
			case "online" -> Integer.toString(Bukkit.getOnlinePlayers().size());
			case "max" -> Integer.toString(Bukkit.getMaxPlayers());
			case "tps" -> String.format(Locale.US, "%.2f", currentTps());
			case "player_ping" -> Long.toString(playerPing(player));
			case "latency" -> Long.toString(currentLatencyMillis());
			case "uptime" -> Formatters.duration(Duration.ofMillis(currentUptimeMillis()));
			case "uptime_ms" -> Long.toString(currentUptimeMillis());
			case "system_cpu" -> formatPercent(systemCpuPercent());
			case "process_cpu" -> formatPercent(processCpuPercent());
			case "version" -> Bukkit.getVersion();
			case "display" -> currentDisplayName();
			case "color" -> currentAccentColor();
			case "whitelist" -> Boolean.toString(Bukkit.hasWhitelist());
			default -> null;
		};
		if (value != null) {
			return value;
		}

		value = resolveCurrentBar(normalized, "tps_bar", width -> buildBar(LunaProgressBarPresets.tps("tps", currentTps()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "player_ping_bar", width -> buildBar(LunaProgressBarPresets.latency("ping", playerPing(player)), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "latency_bar", width -> buildBar(LunaProgressBarPresets.latency("latency", currentLatencyMillis()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "system_cpu_bar", width -> buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "process_cpu_bar", width -> buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "ram_bar", width -> buildBar(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes()), width));
		if (value != null) {
			return value;
		}

		value = resolveCurrentBar(normalized, "tps_bar_only", width -> buildBarOnly(LunaProgressBarPresets.tps("tps", currentTps()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "player_ping_bar_only", width -> buildBarOnly(LunaProgressBarPresets.latency("ping", playerPing(player)), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "latency_bar_only", width -> buildBarOnly(LunaProgressBarPresets.latency("latency", currentLatencyMillis()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "system_cpu_bar_only", width -> buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "process_cpu_bar_only", width -> buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		return resolveCurrentBar(normalized, "ram_bar_only", width -> buildBarOnly(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes()), width));
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

	private String statusText() {
		if (Bukkit.hasWhitelist()) {
			return "MAINT";
		}
		return "ONLINE";
	}

	private double currentTps() {
		try {
			Spark spark = SparkProvider.get();
			double sparkTps = spark.tps().poll(TicksPerSecond.SECONDS_10);
			if (sparkTps > 0D) {
				return sparkTps;
			}
		} catch (Throwable ignored) {
		}

		try {
			double[] values = Bukkit.getTPS();
			if (values.length > 0) {
				return Math.max(0D, values[0]);
			}
		} catch (Throwable ignored) {
		}

		return 0D;
	}

	private long currentLatencyMillis() {
		return currentSnapshotStatus()
			.map(BackendServerStatus::stats)
			.map(stats -> Math.max(0L, stats.heartbeatLatencyMillis()))
			.orElse(0L);
	}

	private long currentUptimeMillis() {
		long runtimeUptime = 0L;
		try {
			runtimeUptime = Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime());
		} catch (Throwable ignored) {
		}

		long heartbeatUptime = currentSnapshotStatus()
			.map(BackendServerStatus::stats)
			.map(stats -> Math.max(0L, stats.uptimeMillis()))
			.orElse(0L);
		return Math.max(runtimeUptime, heartbeatUptime);
	}

	private long currentRamUsedBytes() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
	}

	private long currentRamMaxBytes() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0L, runtime.maxMemory());
	}

	private String currentDisplayName() {
		return currentSnapshotStatus()
			.map(BackendServerStatus::serverDisplay)
			.filter(value -> value != null && !value.isBlank())
			.orElse(currentServerName());
	}

	private String currentAccentColor() {
		return currentSnapshotStatus()
			.map(BackendServerStatus::serverAccentColor)
			.filter(value -> value != null && !value.isBlank())
			.orElse("#F1FF68");
	}

	private Optional<BackendServerStatus> currentSnapshotStatus() {
		int currentPort = plugin.getServer().getPort();
		for (BackendServerStatus status : statusView.snapshot().values()) {
			if (status == null || status.stats() == null) {
				continue;
			}
			if (status.stats().serverPort() == currentPort) {
				return Optional.of(status);
			}
		}
		return Optional.empty();
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

	private double systemCpuPercent() {
		double sparkCpu = readSparkSystemCpuPercent();
		if (sparkCpu >= 0D) {
			return sparkCpu;
		}

		return currentSnapshotStatus()
			.map(BackendServerStatus::stats)
			.map(stats -> Math.max(0D, Math.min(100D, stats.systemCpuUsagePercent())))
			.orElse(0D);
	}

	private double processCpuPercent() {
		double sparkCpu = readSparkProcessCpuPercent();
		if (sparkCpu >= 0D) {
			return sparkCpu;
		}

		return currentSnapshotStatus()
			.map(BackendServerStatus::stats)
			.map(stats -> Math.max(0D, Math.min(100D, stats.processCpuUsagePercent())))
			.orElse(0D);
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
		double percent = raw <= 1D ? raw * 100D : raw;
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
