package dev.belikhun.luna.core.neoforge.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendRegistryClient;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusStore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.neoforge.config.NeoForgeCoreRuntimeConfig;
import dev.belikhun.luna.core.neoforge.config.NeoForgeForwardingSecretResolver;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * NeoForge's adapter around {@link BackendRegistryClient}. It collects this
 * server's stats on the server thread and owns the publish schedule; the
 * conversation with the proxy is the shared client's, identical to Paper's.
 */
public final class NeoForgeHeartbeatPublisher implements AutoCloseable {
	private final MinecraftServer server;
	private final LunaLogger logger;
	private final NeoForgeCoreRuntimeConfig.HeartbeatConfig config;
	private final AmqpMessagingConfig amqpMessagingConfig;
	private final BackendStatusStore statusStore;
	private final BackendRegistryClient registryClient;
	private final ScheduledExecutorService executor;
	private final long bootEpochMillis;

	private volatile ScheduledFuture<?> task;
	private volatile int readTimeoutMillis;

	public NeoForgeHeartbeatPublisher(
		MinecraftServer server,
		LunaLogger logger,
		NeoForgeCoreRuntimeConfig.HeartbeatConfig config,
		AmqpMessagingConfig amqpMessagingConfig,
		BackendStatusStore statusStore
	) {
		this.server = server;
		this.logger = logger.scope("Heartbeat");
		this.config = config == null ? NeoForgeCoreRuntimeConfig.HeartbeatConfig.defaults() : config.sanitize();
		this.amqpMessagingConfig = amqpMessagingConfig == null ? AmqpMessagingConfig.disabled() : amqpMessagingConfig.sanitize();
		this.statusStore = statusStore == null ? new BackendStatusStore(logger, server::execute) : statusStore;
		this.registryClient = new BackendRegistryClient(logger, this.statusStore);
		this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-neoforge-heartbeat");
			thread.setDaemon(true);
			return thread;
		});
		this.bootEpochMillis = System.currentTimeMillis();
		this.task = null;
		this.readTimeoutMillis = this.config.readTimeoutMillis();
	}

	public BackendRegistryClient registryClient() {
		return registryClient;
	}

	public BackendMetadata currentBackendMetadata() {
		return statusStore.currentBackendMetadata().orElse(null);
	}

	public void start() {
		stop();
		if (!config.enabled()) {
			logger.debug("Heartbeat backend->velocity đang tắt trong cấu hình NeoForge.");
			return;
		}

		String secret = NeoForgeForwardingSecretResolver.resolve(logger);
		if (secret.isBlank()) {
			return;
		}

		String serverName = resolveServerName();
		URI uri;
		try {
			String endpoint = config.endpoint();
			String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
			uri = URI.create(base + "/" + encodePath(serverName));
		} catch (Exception exception) {
			logger.warn("Heartbeat endpoint NeoForge không hợp lệ: " + config.endpoint());
			return;
		}

		readTimeoutMillis = config.readTimeoutMillis();
		registryClient.start(
			uri,
			secret,
			config.connectTimeoutMillis(),
			readTimeoutMillis,
			true,
			config.transportLoggingEnabled(),
			this::collectStatsQuietly
		);

		task = executor.scheduleAtFixedRate(
			this::publishTick,
			1L,
			Math.max(1L, config.intervalSeconds()),
			TimeUnit.SECONDS
		);
		logger.success("Đã bật heartbeat NeoForge tới Velocity endpoint=" + uri);
		publishNow();
	}

	public void publishNow() {
		if (task == null) {
			return;
		}
		executor.execute(() -> registryClient.publish(true));
	}

	public void setSelectorPayloadConsumer(Consumer<byte[]> selectorPayloadConsumer) {
		registryClient.setSelectorPayloadConsumer(selectorPayloadConsumer);
	}

	public void syncServerSelectorConfigNow() {
		executor.execute(registryClient::syncSelectorConfigNow);
	}

	public void stop() {
		stopInternal(false);
	}

	public void shutdown() {
		stopInternal(true);
		executor.shutdownNow();
	}

	@Override
	public void close() {
		shutdown();
	}

	private void stopInternal(boolean sendOfflineMarker) {
		ScheduledFuture<?> currentTask = task;
		task = null;
		if (currentTask != null) {
			currentTask.cancel(false);
		}

		if (sendOfflineMarker) {
			registryClient.publish(false);
		}

		registryClient.stop();
	}

	private void publishTick() {
		registryClient.publish(true);

		// same fallback as Paper: an unchanged layout costs one checksum compare
		registryClient.syncSelectorConfigNow();
	}

	/**
	 * Stats collection hops to the server thread, and the shared client wants a
	 * plain supplier — a failure there means one skipped beat, not a broken one.
	 */
	private BackendHeartbeatStats collectStatsQuietly() {
		try {
			return collectStats(true);
		} catch (Exception exception) {
			logger.debug("Không thu thập được stats NeoForge: " + exception.getMessage());
			return collectStatsOnServer(false);
		}
	}

	private BackendHeartbeatStats collectStats(boolean online) throws Exception {
		if (isServerThread()) {
			return collectStatsOnServer(online);
		}

		CompletableFuture<BackendHeartbeatStats> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				future.complete(collectStatsOnServer(online));
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		return future.get(Math.max(1000L, readTimeoutMillis), TimeUnit.MILLISECONDS);
	}

	private boolean isServerThread() {
		try {
			Object value = server.getClass().getMethod("isSameThread").invoke(server);
			if (value instanceof Boolean flag) {
				return flag;
			}
		} catch (ReflectiveOperationException ignored) {
		}
		return false;
	}

	private BackendHeartbeatStats collectStatsOnServer(boolean online) {
		long now = System.currentTimeMillis();
		long uptimeMillis = Math.max(0L, now - bootEpochMillis);
		Runtime runtime = Runtime.getRuntime();
		long ramUsedBytes = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
		long ramMaxBytes = Math.max(0L, runtime.maxMemory());
		long ramFreeBytes = Math.max(0L, ramMaxBytes - ramUsedBytes);
		NeoForgeSparkMetrics.Snapshot sparkMetrics = NeoForgeSparkMetrics.collect(
			logger,
			this::currentTps,
			this::currentSystemCpuUsagePercent,
			this::currentProcessCpuUsagePercent
		);
		PlayerList playerList = server.getPlayerList();
		int onlinePlayers = online && playerList != null ? playerList.getPlayers().size() : 0;
		int maxPlayers = resolveInt(server, "getMaxPlayers", onlinePlayers);

		return new BackendHeartbeatStats(
			resolveString(server, "getServerModName", "NeoForge"),
			SharedConstants.getCurrentVersion().getName(),
			resolveInt(server, "getPort", 0),
			uptimeMillis,
			sparkMetrics.tps(),
			onlinePlayers,
			maxPlayers,
			resolveString(server, "getMotd", ""),
			server.isEnforceWhitelist(),
			sparkMetrics.systemCpuUsagePercent(),
			sparkMetrics.processCpuUsagePercent(),
			ramUsedBytes,
			ramFreeBytes,
			ramMaxBytes,
			0L
		);
	}

	private String resolveServerName() {
		String configured = config.serverName();
		if (configured != null && !configured.isBlank()) {
			return configured;
		}

		String amqpServerName = amqpMessagingConfig.effectiveLocalServerName("");
		if (!amqpServerName.isBlank()) {
			return amqpServerName;
		}

		int port = resolveInt(server, "getPort", 0);
		return "127.0.0.1:" + (port > 0 ? port : 25565);
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
			try {
				Object value = bean.getClass().getMethod("getProcessCpuLoad").invoke(bean);
				if (value instanceof Number number) {
					double raw = number.doubleValue();
					if (raw >= 0D) {
						return normalizeCpuPercent(raw);
					}
				}
			} catch (ReflectiveOperationException ignored) {
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

	private Double invokeDouble(Object target, String methodName) {
		if (target == null) {
			return null;
		}

		try {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			if (value instanceof Number number) {
				return number.doubleValue();
			}
		} catch (ReflectiveOperationException ignored) {
		}
		return null;
	}

	private int resolveInt(Object target, String methodName, int fallback) {
		if (target == null) {
			return fallback;
		}

		try {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			if (value instanceof Number number) {
				return number.intValue();
			}
		} catch (ReflectiveOperationException ignored) {
		}
		return fallback;
	}

	private String resolveString(Object target, String methodName, String fallback) {
		if (target == null) {
			return fallback;
		}

		try {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			if (value != null) {
				String text = String.valueOf(value).trim();
				if (!text.isBlank()) {
					return text;
				}
			}
		} catch (ReflectiveOperationException ignored) {
		}
		return fallback;
	}

	private Object readField(Object target, String fieldName) {
		if (target == null) {
			return null;
		}

		Class<?> type = target.getClass();
		while (type != null) {
			try {
				var field = type.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field.get(target);
			} catch (ReflectiveOperationException ignored) {
				type = type.getSuperclass();
			}
		}
		return null;
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
