package dev.belikhun.luna.legacy.heartbeat;

import dev.belikhun.luna.legacy.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.legacy.config.ForwardingSecretResolver;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns a 1.12.2 backend's publish schedule to the proxy.
 *
 * Stats are collected on the server thread and the conversation itself belongs to
 * {@link BackendRegistryClient}; everything the game version can change is behind
 * {@link BackendServerProbe}.
 *
 * There is no spark here, unlike the modern api's publisher. Spark's API artifact is
 * not on this line's classpath, and the class that reads it must link on a server
 * without it - so rather than carry an availability probe that can only ever answer
 * "no", tick rate comes from {@link BackendServerProbe#tps()} and CPU from the OS
 * bean. If a 1.12.2 spark build is ever wired in, this is the one place to change.
 */
public final class BackendHeartbeatPublisher implements AutoCloseable {
	private final BackendServerProbe probe;
	private final LunaLogger logger;
	private final BackendCoreRuntimeConfig.HeartbeatConfig config;
	private final BackendStatusStore statusStore;
	private final BackendRegistryClient registryClient;
	private final ScheduledExecutorService executor;
	private final long bootEpochMillis;

	private volatile ScheduledFuture<?> task;
	private volatile int readTimeoutMillis;

	public BackendHeartbeatPublisher(
		BackendServerProbe probe,
		LunaLogger logger,
		BackendCoreRuntimeConfig.HeartbeatConfig config,
		BackendStatusStore statusStore,
		final String threadName
	) {
		this.probe = probe;
		this.logger = logger.scope("Heartbeat");
		this.config = config == null ? BackendCoreRuntimeConfig.HeartbeatConfig.defaults() : config.sanitize();
		this.statusStore = statusStore == null ? new BackendStatusStore(logger, new ProbeExecutor(probe)) : statusStore;
		this.registryClient = new BackendRegistryClient(logger, this.statusStore);
		this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, threadName);

				thread.setDaemon(true);

				return thread;
			}
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

	/**
	 * What this backend calls itself: the proxy's row when there is one, the
	 * configured name otherwise.
	 *
	 * Everything that has to name this server - the AMQP queue, the messenger's
	 * presence, the {@code current_server} placeholder - reads it through here, so
	 * none of them can be built holding a name from before the proxy answered.
	 */
	public BackendIdentity identity() {
		return new BackendIdentity() {
			@Override
			public BackendMetadata current() {
				Optional<BackendMetadata> fromProxy = statusStore.currentBackendMetadata();

				if (fromProxy.isPresent() && !fromProxy.get().isBlank()) {
					return fromProxy.get();
				}

				return new BackendMetadata(resolveServerName(), "", "").sanitize();
			}
		};
	}

	public void start() {
		stop();

		if (!config.enabled()) {
			logger.debug("Heartbeat backend->velocity đang tắt trong cấu hình.");

			return;
		}

		String secret = ForwardingSecretResolver.resolve(probe.configDir(), logger);

		if (Strings.isBlank(secret)) {
			return;
		}

		String serverName = resolveServerName();
		URI uri;

		try {
			String endpoint = config.endpoint();
			String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;

			uri = URI.create(base + "/" + encodePath(serverName));
		} catch (Exception exception) {
			logger.warn("Heartbeat endpoint không hợp lệ: " + config.endpoint());

			return;
		}

		readTimeoutMillis = config.readTimeoutMillis();
		registryClient.start(
			uri,
			secret,
			config.connectTimeoutMillis(),
			readTimeoutMillis,
			config.streamEnabled(),
			config.transportLoggingEnabled(),
			new Supplier<BackendHeartbeatStats>() {
				@Override
				public BackendHeartbeatStats get() {
					return collectStatsQuietly();
				}
			}
		);

		// the first sync runs off the server thread: the proxy may not be up yet, and
		// a blocking fetch during server start stalls the boot
		executor.execute(new Runnable() {
			@Override
			public void run() {
				registryClient.syncSelectorConfigNow();
				registryClient.syncMessagingConfigNow();
			}
		});

		task = executor.scheduleAtFixedRate(
			new Runnable() {
				@Override
				public void run() {
					publishTick();
				}
			},
			1L,
			Math.max(1L, config.intervalSeconds()),
			TimeUnit.SECONDS
		);

		logger.success("Đã bật heartbeat tới Velocity endpoint=" + uri);
		publishNow();
	}

	public void publishNow() {
		if (task == null) {
			return;
		}

		executor.execute(new Runnable() {
			@Override
			public void run() {
				registryClient.publish(true);
			}
		});
	}

	public void setSelectorPayloadConsumer(Consumer<byte[]> selectorPayloadConsumer) {
		registryClient.setSelectorPayloadConsumer(selectorPayloadConsumer);
	}

	/**
	 * Where this backend's AMQP settings come from: the proxy. The messaging bus is a
	 * separate mod, so it wires itself once the core has published this publisher.
	 */
	public void setMessagingConfigConsumer(Consumer<byte[]> messagingConfigConsumer) {
		registryClient.setMessagingConfigConsumer(messagingConfigConsumer);
	}

	public void syncServerSelectorConfigNow() {
		executor.execute(new Runnable() {
			@Override
			public void run() {
				registryClient.syncSelectorConfigNow();
			}
		});
	}

	public void syncMessagingConfigNow() {
		executor.execute(new Runnable() {
			@Override
			public void run() {
				registryClient.syncMessagingConfigNow();
			}
		});
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

		// an unchanged layout costs one checksum compare
		registryClient.syncSelectorConfigNow();
	}

	/**
	 * Stats collection hops to the server thread, and the shared client wants a plain
	 * supplier - a failure there means one skipped beat, not a broken one.
	 */
	private BackendHeartbeatStats collectStatsQuietly() {
		try {
			return collectStats(true);
		} catch (Exception exception) {
			logger.debug("Không thu thập được stats: " + exception.getMessage());

			return collectStatsOnServer(false);
		}
	}

	private BackendHeartbeatStats collectStats(final boolean online) throws Exception {
		if (probe.isServerThread()) {
			return collectStatsOnServer(online);
		}

		final CompletableFuture<BackendHeartbeatStats> future = new CompletableFuture<BackendHeartbeatStats>();

		probe.execute(new Runnable() {
			@Override
			public void run() {
				try {
					future.complete(collectStatsOnServer(online));
				} catch (Throwable throwable) {
					future.completeExceptionally(throwable);
				}
			}
		});

		return future.get(Math.max(1000L, readTimeoutMillis), TimeUnit.MILLISECONDS);
	}

	private BackendHeartbeatStats collectStatsOnServer(boolean online) {
		long now = System.currentTimeMillis();
		long uptimeMillis = Math.max(0L, now - bootEpochMillis);
		Runtime runtime = Runtime.getRuntime();
		long ramUsedBytes = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
		long ramMaxBytes = Math.max(0L, runtime.maxMemory());
		long ramFreeBytes = Math.max(0L, ramMaxBytes - ramUsedBytes);
		int onlinePlayers = online ? Math.max(0, probe.onlinePlayers()) : 0;

		return new BackendHeartbeatStats(
			probe.serverModName(),
			probe.gameVersion(),
			probe.port(),
			uptimeMillis,
			probe.tps(),
			onlinePlayers,
			Math.max(onlinePlayers, probe.maxPlayers()),
			probe.motd(),
			probe.whitelistEnforced(),
			currentSystemCpuUsagePercent(),
			currentProcessCpuUsagePercent(),
			ramUsedBytes,
			ramFreeBytes,
			ramMaxBytes,
			0L
		);
	}

	private String resolveServerName() {
		String configured = config.serverName();

		if (Strings.hasText(configured)) {
			return configured;
		}

		int port = probe.port();

		return "127.0.0.1:" + (port > 0 ? port : 25565);
	}

	private double currentSystemCpuUsagePercent() {
		Object bean = ManagementFactory.getOperatingSystemMXBean();

		// getCpuLoad is the modern spelling and getSystemCpuLoad the one a Java 8 JVM
		// actually has; asking for both keeps this working if the runtime is newer
		for (String methodName : new String[] { "getCpuLoad", "getSystemCpuLoad" }) {
			double percent = cpuPercentFrom(bean, methodName);

			if (percent >= 0D) {
				return percent;
			}
		}

		return 0D;
	}

	private double currentProcessCpuUsagePercent() {
		double percent = cpuPercentFrom(ManagementFactory.getOperatingSystemMXBean(), "getProcessCpuLoad");

		return percent >= 0D ? percent : 0D;
	}

	/** The reading as a percentage, or a negative number when unavailable. */
	private double cpuPercentFrom(Object bean, String methodName) {
		try {
			Object value = bean.getClass().getMethod(methodName).invoke(bean);

			if (value instanceof Number && ((Number) value).doubleValue() >= 0D) {
				return normalizeCpuPercent(((Number) value).doubleValue());
			}
		} catch (ReflectiveOperationException ignored) {
			// the bean is a platform-specific implementation; a missing reading is normal
		} catch (RuntimeException ignored) {
			// com.sun.management beans refuse access under some security managers
		}

		return -1D;
	}

	private double normalizeCpuPercent(double raw) {
		if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0D) {
			return 0D;
		}

		double percent = raw <= 1D ? raw * 100D : raw;

		return Math.max(0D, Math.min(100D, percent));
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

	/** Hops a status-store listener onto the server thread. */
	private static final class ProbeExecutor implements java.util.concurrent.Executor {
		private final BackendServerProbe probe;

		ProbeExecutor(BackendServerProbe probe) {
			this.probe = probe;
		}

		@Override
		public void execute(Runnable command) {
			probe.execute(command);
		}
	}
}
