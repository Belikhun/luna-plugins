package dev.belikhun.luna.tabbridge.neoforge;

import com.sun.management.OperatingSystemMXBean;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class NeoForgeTabBridgePlaceholderUpdater implements AutoCloseable {
	private static final int DEFAULT_BAR_WIDTH = 25;
	private static final long REFRESH_INTERVAL_SECONDS = 2L;
	private static final String DEFAULT_COLOR = "#F1FF68";

	private final LunaLogger logger;
	private final MinecraftServer server;
	private final NeoForgeTabBridgeRuntime runtime;
	private final String localServerName;
	private final ScheduledExecutorService refreshExecutor;

	NeoForgeTabBridgePlaceholderUpdater(LunaLogger logger, MinecraftServer server, NeoForgeTabBridgeRuntime runtime, String localServerName) {
		this.logger = logger.scope("Placeholders");
		this.server = Objects.requireNonNull(server, "server");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.localServerName = localServerName == null || localServerName.isBlank() ? "backend" : localServerName;
		this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-tabbridge-neoforge-placeholders");
			thread.setDaemon(true);
			return thread;
		});
		this.refreshExecutor.scheduleAtFixedRate(
			() -> this.server.execute(this::refreshOnlinePlayers),
			REFRESH_INTERVAL_SECONDS,
			REFRESH_INTERVAL_SECONDS,
			TimeUnit.SECONDS
		);
	}

	void refreshOnlinePlayers() {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			refreshPlayer(player);
		}
	}

	void refreshPlayer(ServerPlayer player) {
		if (player == null) {
			return;
		}

		runtime.updatePlayerPlaceholders(player, snapshotFor(player));
	}

	private Map<String, String> snapshotFor(ServerPlayer player) {
		Map<String, String> values = new LinkedHashMap<>();
		int playerPingMillis = playerPingMillis(player);
		double currentTps = currentTps();
		putCore(values, "current_server", localServerName);
		putCore(values, "status", server.isEnforceWhitelist() ? "MAINT" : "ONLINE");
		putCore(values, "online", Integer.toString(server.getPlayerCount()));
		putCore(values, "max", Integer.toString(server.getMaxPlayers()));
		putCore(values, "tps", formatTps(currentTps));
		putCore(values, "player_ping", Integer.toString(playerPingMillis));
		putCore(values, "latency", "0");
		putCore(values, "uptime", Formatters.compactDuration(Duration.ofMillis(currentUptimeMillis())));
		putCore(values, "uptime_ms", Long.toString(currentUptimeMillis()));
		putCore(values, "system_cpu", formatPercent(systemCpuPercent()));
		putCore(values, "process_cpu", formatPercent(processCpuPercent()));
		putCore(values, "version", safe(server.getServerVersion()));
		putCore(values, "display", localServerName);
		putCore(values, "color", DEFAULT_COLOR);
		putCore(values, "whitelist", Boolean.toString(server.isEnforceWhitelist()));

		putCore(values, "tps_bar", buildBar(LunaProgressBarPresets.tps("tps", currentTps), DEFAULT_BAR_WIDTH));
		putCore(values, "player_ping_bar", buildBar(LunaProgressBarPresets.latency("ping", playerPingMillis), DEFAULT_BAR_WIDTH));
		putCore(values, "latency_bar", buildBar(LunaProgressBarPresets.latency("latency", 0D), DEFAULT_BAR_WIDTH));
		putCore(values, "system_cpu_bar", buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "process_cpu_bar", buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "ram_bar", buildBar(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes()), DEFAULT_BAR_WIDTH));

		putCore(values, "tps_bar_only", buildBarOnly(LunaProgressBarPresets.tps("tps", currentTps), DEFAULT_BAR_WIDTH));
		putCore(values, "player_ping_bar_only", buildBarOnly(LunaProgressBarPresets.latency("ping", playerPingMillis), DEFAULT_BAR_WIDTH));
		putCore(values, "latency_bar_only", buildBarOnly(LunaProgressBarPresets.latency("latency", 0D), DEFAULT_BAR_WIDTH));
		putCore(values, "system_cpu_bar_only", buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "process_cpu_bar_only", buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "ram_bar_only", buildBarOnly(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes()), DEFAULT_BAR_WIDTH));

		putCore(values, "tps_bar_value_only", buildValueOnly(LunaProgressBarPresets.tps("tps", currentTps)));
		putCore(values, "player_ping_bar_value_only", buildValueOnly(LunaProgressBarPresets.latency("ping", playerPingMillis)));
		putCore(values, "latency_bar_value_only", buildValueOnly(LunaProgressBarPresets.latency("latency", 0D)));
		putCore(values, "system_cpu_bar_value_only", buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", systemCpuPercent())));
		putCore(values, "process_cpu_bar_value_only", buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", processCpuPercent())));
		putCore(values, "ram_bar_value_only", buildValueOnly(LunaProgressBarPresets.ram("ram", currentRamUsedBytes(), currentRamMaxBytes())));
		return Map.copyOf(values);
	}

	private void putCore(Map<String, String> values, String key, String value) {
		String normalized = safe(value);
		values.put(key, normalized);
		values.put("luna_" + key, normalized);
		values.put(key + "_safe", escapePlaceholderPercents(normalized));
		values.put("luna_" + key + "_safe", escapePlaceholderPercents(normalized));
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

	private int clampWidth(int width) {
		return Math.max(1, Math.min(120, width));
	}

	private double currentTps() {
		for (String methodName : new String[] {"getAverageTickTime", "getCurrentSmoothedTickTime", "getTickTime"}) {
			Double averageTickTime = invokeDouble(server, methodName);
			if (averageTickTime == null || averageTickTime <= 0D) {
				continue;
			}

			return Math.max(0D, Math.min(20D, 1000D / averageTickTime));
		}

		Object tickTimes = readField(server, "tickTimes");
		if (tickTimes instanceof long[] values && values.length > 0) {
			long total = 0L;
			int samples = 0;
			for (long value : values) {
				if (value <= 0L) {
					continue;
				}
				total += value;
				samples++;
			}

			if (samples > 0) {
				double averageTickTimeMillis = (total / (double) samples) / 1_000_000D;
				if (averageTickTimeMillis > 0D) {
					return Math.max(0D, Math.min(20D, 1000D / averageTickTimeMillis));
				}
			}
		}

		return 20D;
	}

	private int playerPingMillis(ServerPlayer player) {
		if (player == null) {
			return 0;
		}

		for (String methodName : new String[] {"latency", "getLatency", "connectionLatency", "getConnectionLatency"}) {
			Integer directValue = invokeInt(player, methodName);
			if (directValue != null && directValue >= 0) {
				return directValue;
			}
		}

		Object connection = readField(player, "connection");
		if (connection != null) {
			for (String methodName : new String[] {"latency", "getLatency", "connectionLatency", "getConnectionLatency"}) {
				Integer connectionValue = invokeInt(connection, methodName);
				if (connectionValue != null && connectionValue >= 0) {
					return connectionValue;
				}
			}

			for (String fieldName : new String[] {"latency", "connectionLatency"}) {
				Object value = readField(connection, fieldName);
				if (value instanceof Number number) {
					return Math.max(0, number.intValue());
				}
			}
		}

		for (String fieldName : new String[] {"latency", "connectionLatency"}) {
			Object value = readField(player, fieldName);
			if (value instanceof Number number) {
				return Math.max(0, number.intValue());
			}
		}

		return 0;
	}

	private Double invokeDouble(Object target, String methodName) {
		Object value = invokeNoArg(target, methodName);
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		return null;
	}

	private Integer invokeInt(Object target, String methodName) {
		Object value = invokeNoArg(target, methodName);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private Object invokeNoArg(Object target, String methodName) {
		if (target == null) {
			return null;
		}

		try {
			Method method = target.getClass().getMethod(methodName);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private Object readField(Object target, String fieldName) {
		if (target == null) {
			return null;
		}

		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field.get(target);
			} catch (ReflectiveOperationException ignored) {
				type = type.getSuperclass();
			}
		}

		return null;
	}

	private long currentUptimeMillis() {
		try {
			return Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime());
		} catch (Throwable throwable) {
			logger.debug("Không thể đọc uptime hiện tại: " + throwable.getMessage());
			return 0L;
		}
	}

	private long currentRamUsedBytes() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
	}

	private long currentRamMaxBytes() {
		return Math.max(1L, Runtime.getRuntime().maxMemory());
	}

	private double systemCpuPercent() {
		return cpuPercent(OperatingSystemMXBean::getCpuLoad);
	}

	private double processCpuPercent() {
		return cpuPercent(OperatingSystemMXBean::getProcessCpuLoad);
	}

	private double cpuPercent(java.util.function.ToDoubleFunction<OperatingSystemMXBean> extractor) {
		try {
			java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
			if (bean instanceof OperatingSystemMXBean operatingSystemBean) {
				double load = extractor.applyAsDouble(operatingSystemBean);
				if (!Double.isNaN(load) && !Double.isInfinite(load) && load >= 0D) {
					return Math.max(0D, Math.min(100D, load * 100D));
				}
			}
		} catch (Throwable throwable) {
			logger.debug("Không thể đọc CPU load hiện tại: " + throwable.getMessage());
		}
		return 0D;
	}

	private String formatPercent(double value) {
		return String.format(Locale.US, "%.1f%%", Math.max(0D, value));
	}

	private String formatTps(double value) {
		return String.format(Locale.US, "%.2f", Math.max(0D, value));
	}

	private String escapePlaceholderPercents(String value) {
		return safe(value).replace("%", "%%");
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	@Override
	public void close() {
		refreshExecutor.shutdownNow();
	}
}
