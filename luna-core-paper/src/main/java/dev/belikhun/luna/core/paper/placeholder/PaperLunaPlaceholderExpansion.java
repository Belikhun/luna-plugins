package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperLunaPlaceholderExpansion extends PlaceholderExpansion {
	private static final int DEFAULT_BAR_WIDTH = 25;
	private static final int MIN_BAR_WIDTH = 1;
	private static final int MAX_BAR_WIDTH = 120;
	private static final String SAFE_SUFFIX = "_safe";
	private static final Pattern WORLD_WEATHER_PATTERN = Pattern.compile("^world_(.+)_(weather|weathericon|weathercolor|weatherduration)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PLAYER_STATUS_PATTERN = Pattern.compile("^player_status(?:_(.+))?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("^stripcolor_(legacy|mm)_(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern MM2L_PATTERN = Pattern.compile("^mm2l_(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern BRACKET_PATTERN = Pattern.compile("\\{[^{}]+}");

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

		String raw = params.trim();
		String normalized = raw.toLowerCase(Locale.ROOT);
		boolean safeVariant = normalized.endsWith(SAFE_SUFFIX) && normalized.length() > SAFE_SUFFIX.length();
		String rawLookupKey = safeVariant
			? raw.substring(0, raw.length() - SAFE_SUFFIX.length())
			: raw;
		String normalizedLookupKey = safeVariant
			? normalized.substring(0, normalized.length() - SAFE_SUFFIX.length())
			: normalized;
		String value = resolveCurrent(player, rawLookupKey, normalizedLookupKey);
		if (safeVariant && value != null) {
			value = escapePlaceholderPercents(value);
		}
		return value == null ? "" : value;
	}

	private String resolveCurrent(OfflinePlayer player, String rawKey, String normalized) {
		String importedValue = resolveImportedPlaceholder(player, rawKey, normalized);
		if (importedValue != null) {
			return importedValue;
		}

		String value = switch (normalized) {
			case "current_server" -> currentServerName();
			case "host_name", "server_name" -> currentServerInfoName();
			case "status" -> statusText();
			case "online" -> Integer.toString(Bukkit.getOnlinePlayers().size());
			case "max" -> Integer.toString(Bukkit.getMaxPlayers());
			case "tps" -> String.format(Locale.US, "%.2f", currentTps());
			case "player_ping" -> Long.toString(playerPing(player));
			case "latency" -> Long.toString(currentLatencyMillis());
			case "uptime" -> Formatters.compactDuration(Duration.ofMillis(currentUptimeMillis()));
			case "uptime_long" -> Formatters.duration(Duration.ofMillis(currentUptimeMillis()));
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
		value = resolveExact(normalized, "tps_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.tps("tps", currentTps())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "player_ping_bar_only", width -> buildBarOnly(LunaProgressBarPresets.latency("ping", playerPing(player)), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(normalized, "player_ping_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.latency("ping", playerPing(player))));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "latency_bar_only", width -> buildBarOnly(LunaProgressBarPresets.latency("latency", currentLatencyMillis()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(normalized, "latency_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.latency("latency", currentLatencyMillis())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "system_cpu_bar_only", width -> buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(normalized, "system_cpu_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "process_cpu_bar_only", width -> buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(normalized, "process_cpu_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(normalized, "ram_bar_only", width -> buildBarOnly(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes()), width));
		if (value != null) {
			return value;
		}
		return resolveExact(normalized, "ram_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes())));
	}

	private String resolveImportedPlaceholder(OfflinePlayer player, String rawKey, String normalizedKey) {
		String expandedKey = replaceInnerPlaceholders(player, rawKey);
		String expandedNormalized = expandedKey.toLowerCase(Locale.ROOT);

		Matcher worldWeatherMatcher = WORLD_WEATHER_PATTERN.matcher(expandedKey);
		if (worldWeatherMatcher.matches()) {
			String worldName = worldWeatherMatcher.group(1);
			World world = Bukkit.getWorld(worldName);
			if (world == null) {
				return "unknown:" + worldName;
			}

			boolean raining = world.hasStorm();
			boolean thundering = world.isThundering();
			return switch (worldWeatherMatcher.group(2).toLowerCase(Locale.ROOT)) {
				case "weather" -> LunaImportedPlaceholderSupport.weatherText(raining, thundering);
				case "weathericon" -> LunaImportedPlaceholderSupport.weatherIcon(raining, thundering);
				case "weathercolor" -> LunaImportedPlaceholderSupport.weatherColor(raining, thundering);
				case "weatherduration" -> LunaImportedPlaceholderSupport.formatDurationSeconds(Math.floorDiv(Math.max(0L, world.getWeatherDuration()), 20L));
				default -> null;
			};
		}

		if (expandedNormalized.equals("voicechat_status")) {
			return player != null && player.isOnline()
				? LunaImportedPlaceholderSupport.voiceChatStatus(SimpleVoiceChatCompat.playerStatus(player.getUniqueId()))
				: "<white>⛔<reset>";
		}

		if (expandedNormalized.equals("voicechat_group")) {
			return SimpleVoiceChatCompat.playerGroup(player == null ? null : player.getUniqueId());
		}

		if (expandedNormalized.equals("player_level")) {
			Player onlinePlayer = player == null ? null : player.getPlayer();
			return LunaImportedPlaceholderSupport.playerLevel(onlinePlayer == null ? 0 : onlinePlayer.getLevel());
		}

		Matcher playerStatusMatcher = PLAYER_STATUS_PATTERN.matcher(expandedKey);
		if (playerStatusMatcher.matches()) {
			Player onlinePlayer = player == null ? null : player.getPlayer();
			if (onlinePlayer == null || onlinePlayer.getWorld() == null) {
				return "<white>❌<reset>";
			}

			return LunaImportedPlaceholderSupport.playerStatusDot(
				toWorldKind(onlinePlayer.getWorld().getEnvironment()),
				playerStatusMatcher.group(1)
			);
		}

		Matcher stripColorMatcher = STRIP_COLOR_PATTERN.matcher(expandedKey);
		if (stripColorMatcher.matches()) {
			return switch (stripColorMatcher.group(1).toLowerCase(Locale.ROOT)) {
				case "legacy" -> LunaImportedPlaceholderSupport.stripLegacyColors(stripColorMatcher.group(2));
				case "mm" -> LunaImportedPlaceholderSupport.stripMiniMessage(stripColorMatcher.group(2));
				default -> null;
			};
		}

		Matcher mm2lMatcher = MM2L_PATTERN.matcher(expandedKey);
		if (mm2lMatcher.matches()) {
			return LunaImportedPlaceholderSupport.miniMessageToLegacy(mm2lMatcher.group(1));
		}

		return normalizedKey.equals(expandedNormalized) ? null : resolveImportedPlaceholder(player, expandedKey, expandedNormalized);
	}

	private String replaceInnerPlaceholders(OfflinePlayer player, String value) {
		if (value == null || value.isBlank()) {
			return "";
		}

		String resolved = value;
		for (int depth = 0; depth < 8; depth++) {
			if (!BRACKET_PATTERN.matcher(resolved).find()) {
				return resolved;
			}

			String next = PlaceholderAPI.setBracketPlaceholders(player, resolved);
			if (next == null || next.equals(resolved)) {
				return resolved;
			}
			resolved = next;
		}
		return resolved;
	}

	private WorldKind toWorldKind(World.Environment environment) {
		return switch (environment) {
			case NORMAL -> WorldKind.NORMAL;
			case NETHER -> WorldKind.NETHER;
			case THE_END -> WorldKind.END;
			default -> WorldKind.CUSTOM;
		};
	}

	private String resolveCurrentBar(String key, String baseKey, IntFunction<String> renderer) {
		Integer width = parseCurrentWidth(key, baseKey);
		if (width == null) {
			return null;
		}
		return renderer.apply(width);
	}

	private String resolveExact(String key, String exactKey, Supplier<String> renderer) {
		if (!key.equals(exactKey)) {
			return null;
		}
		return renderer.get();
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
		return parsed == null ? null : parsed;
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
		return statusView.currentBackendMetadata()
			.map(BackendMetadata::displayName)
			.filter(value -> value != null && !value.isBlank())
			.orElseGet(() -> currentSnapshotStatus()
				.map(BackendServerStatus::serverDisplay)
				.filter(value -> value != null && !value.isBlank())
				.orElse(currentServerName()));
	}

	private String currentAccentColor() {
		return statusView.currentBackendMetadata()
			.map(BackendMetadata::accentColor)
			.filter(value -> value != null && !value.isBlank())
			.orElseGet(() -> currentSnapshotStatus()
				.map(BackendServerStatus::serverAccentColor)
				.filter(value -> value != null && !value.isBlank())
				.orElse("#F1FF68"));
	}

	private Optional<BackendServerStatus> currentSnapshotStatus() {
		Optional<BackendMetadata> currentMetadata = statusView.currentBackendMetadata();
		if (currentMetadata.isPresent()) {
			Optional<BackendServerStatus> currentStatus = statusView.status(currentMetadata.get().name());
			if (currentStatus.isPresent()) {
				return currentStatus;
			}
		}

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
		return bar.width(clampWidth(width)).renderBar();
	}

	private String buildValueOnly(LunaProgressBar bar) {
		return bar.renderValue();
	}

	private String escapePlaceholderPercents(String value) {
		return value.indexOf('%') >= 0 ? value.replace("%", "%%") : value;
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
		Optional<BackendMetadata> currentMetadata = statusView.currentBackendMetadata();
		if (currentMetadata.isPresent() && currentMetadata.get().name() != null && !currentMetadata.get().name().isBlank()) {
			return currentMetadata.get().name();
		}

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

	private String currentServerInfoName() {
		return statusView.currentBackendMetadata()
			.map(BackendMetadata::serverName)
			.filter(value -> value != null && !value.isBlank())
			.orElseGet(this::currentServerName);
	}
}
