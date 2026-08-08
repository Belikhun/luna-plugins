package dev.belikhun.luna.core.paper.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendRegistryClient;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusStore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.lang.management.ManagementFactory;
import java.util.function.Consumer;

/**
 * Paper's adapter around {@link BackendRegistryClient}: it collects this
 * server's stats and owns the publish schedule, and everything about talking to
 * the proxy — cursor, epoch, the push stream, config sync — lives in the shared
 * client so Paper and NeoForge cannot drift apart again.
 */
public final class PaperHeartbeatPublisher {
	private final Plugin plugin;
	private final ConfigStore configStore;
	private final LunaLogger logger;
	private final BackendRegistryClient registryClient;
	private final long bootEpochMillis;
	private int taskId;
	private volatile int lastReportedPlayerCount;
	private volatile boolean sparkProbeWarned;

	public PaperHeartbeatPublisher(Plugin plugin, ConfigStore configStore, LunaLogger logger, BackendStatusStore statusStore) {
		this.plugin = plugin;
		this.configStore = configStore;
		this.logger = logger.scope("Heartbeat");
		this.registryClient = new BackendRegistryClient(logger, statusStore);
		this.bootEpochMillis = System.currentTimeMillis();
		this.taskId = -1;
		this.lastReportedPlayerCount = -1;
		this.sparkProbeWarned = false;
	}

	public BackendRegistryClient registryClient() {
		return registryClient;
	}

	public void setSelectorPayloadConsumer(Consumer<byte[]> consumer) {
		registryClient.setSelectorPayloadConsumer(consumer);
	}

	public void setMessagingConfigConsumer(Consumer<byte[]> consumer) {
		registryClient.setMessagingConfigConsumer(consumer);
	}

	public void start() {
		stop();
		if (!configStore.get("heartbeat.enabled").asBoolean(true)) {
			logger.debug("Heartbeat backend->velocity đang tắt trong cấu hình.");
			return;
		}

		String endpoint = configStore.get("heartbeat.endpoint").asString("http://127.0.0.1:32452/api/heartbeat").trim();
		String serverName = resolveServerName(plugin, configStore);
		String secret = PaperForwardingSecretResolver.resolve(plugin, logger);
		if (secret.isBlank()) {
			return;
		}

		int intervalSeconds = Math.max(1, configStore.get("heartbeat.intervalSeconds").asInt(5));
		int connectTimeoutMillis = Math.max(500, configStore.get("heartbeat.connectTimeoutMillis").asInt(3000));
		int readTimeoutMillis = Math.max(500, configStore.get("heartbeat.readTimeoutMillis").asInt(3000));
		long intervalTicks = intervalSeconds * 20L;

		URI uri;
		try {
			String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
			uri = URI.create(base + "/" + encodePath(serverName));
		} catch (Exception exception) {
			logger.warn("Heartbeat endpoint không hợp lệ: " + endpoint);
			return;
		}

		lastReportedPlayerCount = Bukkit.getOnlinePlayers().size();
		registryClient.start(
			uri,
			secret,
			connectTimeoutMillis,
			readTimeoutMillis,
			configStore.get("heartbeat.streamEnabled").asBoolean(true),
			configStore.get("logging.heartbeatTransport.enabled").asBoolean(false),
			this::collectStats
		);

		// the very first sync runs off the main thread: the proxy may not be up yet,
		// and a blocking fetch during onEnable stalls the whole server boot
		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
			registryClient.syncSelectorConfigNow();
			registryClient.syncMessagingConfigNow();
		});
		taskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::publishTick, 20L, intervalTicks).getTaskId();
		logger.success("Đã bật heartbeat backend tới Velocity endpoint=" + uri);
	}

	public void syncServerSelectorConfigNow() {
		registryClient.syncSelectorConfigNow();
	}

	public void syncMessagingConfigNow() {
		registryClient.syncMessagingConfigNow();
	}

	public void publishNowIfPlayerCountChanged() {
		if (taskId == -1) {
			return;
		}

		plugin.getServer().getScheduler().runTask(plugin, () -> {
			int currentCount = Bukkit.getOnlinePlayers().size();
			if (currentCount == lastReportedPlayerCount) {
				return;
			}

			lastReportedPlayerCount = currentCount;
			publishNowAsync();
		});
	}

	public void publishNow() {
		if (taskId == -1) {
			return;
		}

		publishNowAsync();
	}

	public void stop() {
		stopInternal(false);
	}

	public void shutdown() {
		stopInternal(true);
	}

	private void stopInternal(boolean sendOfflineMarker) {
		if (taskId != -1) {
			plugin.getServer().getScheduler().cancelTask(taskId);
			taskId = -1;
		}

		if (sendOfflineMarker) {
			registryClient.publish(false);
		}

		registryClient.stop();
	}

	private void publishTick() {
		lastReportedPlayerCount = Bukkit.getOnlinePlayers().size();
		registryClient.publish(true);

		// the selector layout follows a proxy reload without waiting for the stream
		// to notice; an unchanged body is dropped by the client's checksum
		registryClient.syncSelectorConfigNow();
	}

	private void publishNowAsync() {
		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> registryClient.publish(true));
	}

	private BackendHeartbeatStats collectStats() {
		long now = System.currentTimeMillis();
		long uptimeMillis = Math.max(0L, now - bootEpochMillis);

		SparkMetrics metrics = collectSparkMetrics();
		double tps = metrics != null ? metrics.tps() : fallbackTps();
		double systemCpuUsagePercent = metrics != null ? metrics.systemCpuUsagePercent() : currentSystemCpuUsagePercent();
		double processCpuUsagePercent = metrics != null ? metrics.processCpuUsagePercent() : currentProcessCpuUsagePercent();
		long ramUsedBytes = fallbackRamUsed();
		long ramMaxBytes = fallbackRamMax();
		long ramFreeBytes = Math.max(0L, ramMaxBytes - ramUsedBytes);

		return new BackendHeartbeatStats(
			Bukkit.getName(),
			Bukkit.getVersion(),
			plugin.getServer().getPort(),
			uptimeMillis,
			tps,
			Bukkit.getOnlinePlayers().size(),
			Bukkit.getMaxPlayers(),
			plugin.getServer().motd().toString(),
			Bukkit.hasWhitelist(),
			systemCpuUsagePercent,
			processCpuUsagePercent,
			ramUsedBytes,
			ramFreeBytes,
			ramMaxBytes,
			0L
		);
	}

	private double fallbackTps() {
		double tps = 0D;
		try {
			double[] values = Bukkit.getTPS();
			if (values.length > 0) {
				tps = values[0];
			}
		} catch (Throwable ignored) {
		}
		return tps;
	}

	private long fallbackRamUsed() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
	}

	private long fallbackRamMax() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0L, runtime.maxMemory());
	}

	private SparkMetrics collectSparkMetrics() {
		try {
			Spark spark = SparkProvider.get();
			double tps = fallbackTps();
			if (spark.tps() != null) {
				double sparkTps = spark.tps().poll(TicksPerSecond.SECONDS_10);
				if (sparkTps > 0D) {
					tps = sparkTps;
				}
			}

			double systemCpuPercent = normalizeCpuPercent(spark.cpuSystem().poll(CpuUsage.MINUTES_1));
			double processCpuPercent = normalizeCpuPercent(spark.cpuProcess().poll(CpuUsage.MINUTES_1));

			return new SparkMetrics(tps, systemCpuPercent, processCpuPercent);
		} catch (IllegalStateException exception) {
			if (!sparkProbeWarned) {
				sparkProbeWarned = true;
				logger.warn("Spark chưa sẵn sàng, dùng fallback nội bộ cho heartbeat metrics: " + exception.getMessage());
			}
			return null;
		} catch (Throwable throwable) {
			if (!sparkProbeWarned) {
				sparkProbeWarned = true;
				logger.warn("Không thể đọc metrics từ Spark API, dùng fallback nội bộ: " + throwable.getMessage());
			}
			return null;
		}
	}

	private double currentSystemCpuUsagePercent() {
		try {
			Object bean = ManagementFactory.getOperatingSystemMXBean();
			for (String methodName : new String[] {"getCpuLoad", "getSystemCpuLoad"}) {
				try {
					Object value = bean.getClass().getMethod(methodName).invoke(bean);
					if (value instanceof Number number) {
						double raw = number.doubleValue();
						if (raw >= 0D) {
							return normalizeCpuPercent(raw);
						}
					}
				} catch (ReflectiveOperationException ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
		return 0D;
	}

	private double currentProcessCpuUsagePercent() {
		try {
			Object bean = ManagementFactory.getOperatingSystemMXBean();
			for (String methodName : new String[] {"getProcessCpuLoad"}) {
				try {
					Object value = bean.getClass().getMethod(methodName).invoke(bean);
					if (value instanceof Number number) {
						double raw = number.doubleValue();
						if (raw >= 0D) {
							return normalizeCpuPercent(raw);
						}
					}
				} catch (ReflectiveOperationException ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
		return 0D;
	}

	private double normalizeCpuPercent(double raw) {
		if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0D) {
			return 0D;
		}
		double percent = raw <= 1D ? raw * 100D : raw;
		return Math.max(0D, Math.min(100D, percent));
	}

	private record SparkMetrics(
		double tps,
		double systemCpuUsagePercent,
		double processCpuUsagePercent
	) {
	}

	public static String resolveServerName(Plugin plugin, ConfigStore configStore) {
		String configured = configStore.get("heartbeat.serverName").asString("").trim();
		if (!configured.isBlank()) {
			return configured;
		}

		String host = plugin.getServer().getIp();
		if (host == null || host.isBlank()) {
			host = "127.0.0.1";
		}
		return host + ":" + plugin.getServer().getPort();
	}

	private String encodePath(String value) {
		StringBuilder out = new StringBuilder();
		for (char ch : value.toCharArray()) {
			boolean safe = (ch >= 'a' && ch <= 'z')
				|| (ch >= 'A' && ch <= 'Z')
				|| (ch >= '0' && ch <= '9')
				|| ch == '-'
				|| ch == '_'
				|| ch == '.';
			if (safe) {
				out.append(ch);
			} else {
				out.append('%');
				out.append(Integer.toHexString(ch).toUpperCase());
			}
		}
		return out.toString();
	}

}
