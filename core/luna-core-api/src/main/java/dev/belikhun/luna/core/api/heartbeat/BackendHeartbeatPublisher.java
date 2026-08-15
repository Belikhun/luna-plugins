package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.config.BackendCoreRuntimeConfig;
import dev.belikhun.luna.core.api.config.ForwardingSecretResolver;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns a mod-loader backend's publish schedule to the proxy.
 *
 * Stats are collected on the server thread and the conversation itself belongs
 * to {@link BackendRegistryClient}, identical to Paper's; everything the game
 * version can change is behind {@link BackendServerProbe}, which is what lets
 * NeoForge and Fabric share this class instead of keeping a copy each.
 */
public final class BackendHeartbeatPublisher implements AutoCloseable {
	private final BackendServerProbe probe;
	private final LunaLogger logger;
	private final BackendCoreRuntimeConfig.HeartbeatConfig config;
	private final BackendStatusStore statusStore;
	private final BackendRegistryClient registryClient;
	private final ScheduledExecutorService executor;
	private final long bootEpochMillis;

	/**
	 * Owned here rather than per loader: the loaders differ only in what their
	 * tick event is called, and duplicating the window arithmetic four times is
	 * how two of them end up computing a different Apdex.
	 */
	private final TickRecorder tickRecorder = new TickRecorder();

	/** Set by {@link #tickStarted()}; 0 means no tick is open. */
	private long tickStartNanos;

	private volatile ScheduledFuture<?> task;
	private volatile int readTimeoutMillis;

	public BackendHeartbeatPublisher(
		BackendServerProbe probe,
		LunaLogger logger,
		BackendCoreRuntimeConfig.HeartbeatConfig config,
		BackendStatusStore statusStore,
		String threadName
	) {
		this.probe = probe;
		this.logger = logger.scope("Heartbeat");
		this.config = config == null ? BackendCoreRuntimeConfig.HeartbeatConfig.defaults() : config.sanitize();
		this.statusStore = statusStore == null ? new BackendStatusStore(logger, probe::execute) : statusStore;
		this.registryClient = new BackendRegistryClient(logger, this.statusStore);
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, threadName);
			thread.setDaemon(true);
			return thread;
		});
		this.bootEpochMillis = System.currentTimeMillis();
		this.task = null;
		this.readTimeoutMillis = this.config.readTimeoutMillis();
	}

	/**
	 * Fold one tick into the index the next heartbeat reports.
	 *
	 * Called from each loader's own tick hook, on the server thread. A platform
	 * with no such hook simply never calls it, and the heartbeat then reports the
	 * tick indices as absent rather than as perfect.
	 */
	public void recordTick(double millis) {
		tickRecorder.record(millis, probe.onlinePlayers());
	}

	/**
	 * Open a tick, to be closed by {@link #tickEnded()}.
	 *
	 * The pair exists because the loaders' tick events give no duration, only a
	 * before and an after; the gap between two afters is the tick *period*, which
	 * on a healthy server is a flat 50 ms of mostly sleeping and would score every
	 * server as exactly satisfied. Timing between the two is the tick's real cost.
	 */
	public void tickStarted() {
		tickStartNanos = System.nanoTime();
	}

	/** Close the tick opened by {@link #tickStarted()} and record what it cost. */
	public void tickEnded() {
		long started = tickStartNanos;

		if (started == 0L) {
			return;
		}

		tickStartNanos = 0L;
		recordTick((System.nanoTime() - started) / 1_000_000D);
	}

	public BackendRegistryClient registryClient() {
		return registryClient;
	}

	public BackendMetadata currentBackendMetadata() {
		return statusStore.currentBackendMetadata().orElse(null);
	}

	/**
	 * What this backend calls itself, the way Paper resolves it: the proxy's row
	 * when there is one, the configured name otherwise.
	 *
	 * Everything that has to name this server - the AMQP queue, the messenger's
	 * presence, the {@code current_server} placeholder - reads it through here,
	 * so none of them can be built holding a name from before the proxy answered.
	 */
	public BackendIdentity identity() {
		return () -> statusStore.currentBackendMetadata()
			.filter(metadata -> !metadata.isBlank())
			.orElseGet(() -> new BackendMetadata(resolveServerName(), "", "").sanitize());
	}

	public void start() {
		stop();
		if (!config.enabled()) {
			logger.debug("Heartbeat backend->velocity đang tắt trong cấu hình.");
			return;
		}

		String secret = ForwardingSecretResolver.resolve(probe.configDir(), logger);
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
			this::collectStatsQuietly
		);

		// the first sync runs off the server thread, as Paper's does: the proxy may
		// not be up yet, and a blocking fetch during server start stalls the boot
		executor.execute(() -> {
			registryClient.syncSelectorConfigNow();
			registryClient.syncMessagingConfigNow();
		});

		task = executor.scheduleAtFixedRate(
			this::publishTick,
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
		executor.execute(() -> registryClient.publish(true));
	}

	public void setSelectorPayloadConsumer(Consumer<byte[]> selectorPayloadConsumer) {
		registryClient.setSelectorPayloadConsumer(selectorPayloadConsumer);
	}

	/**
	 * Where this backend's AMQP settings come from: the proxy, exactly as on
	 * Paper. The messaging bus is a separate mod here, so it wires itself once
	 * the core has published this publisher.
	 */
	public void setMessagingConfigConsumer(Consumer<byte[]> messagingConfigConsumer) {
		registryClient.setMessagingConfigConsumer(messagingConfigConsumer);
	}

	public void syncServerSelectorConfigNow() {
		executor.execute(registryClient::syncSelectorConfigNow);
	}

	public void syncMessagingConfigNow() {
		executor.execute(registryClient::syncMessagingConfigNow);
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
	 * plain supplier - a failure there means one skipped beat, not a broken one.
	 */
	private BackendHeartbeatStats collectStatsQuietly() {
		try {
			return collectStats(true);
		} catch (Exception exception) {
			logger.debug("Không thu thập được stats: " + exception.getMessage());
			return collectStatsOnServer(false);
		}
	}

	private BackendHeartbeatStats collectStats(boolean online) throws Exception {
		if (probe.isServerThread()) {
			return collectStatsOnServer(online);
		}

		CompletableFuture<BackendHeartbeatStats> future = new CompletableFuture<>();
		probe.execute(() -> {
			try {
				future.complete(collectStatsOnServer(online));
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
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
		SparkMetrics.Snapshot sparkMetrics = SparkMetrics.collect(
			logger,
			probe::tps,
			this::currentSystemCpuUsagePercent,
			this::currentProcessCpuUsagePercent
		);
		int onlinePlayers = online ? Math.max(0, probe.onlinePlayers()) : 0;

		return new BackendHeartbeatStats(
			probe.serverModName(),
			probe.gameVersion(),
			probe.port(),
			uptimeMillis,
			sparkMetrics.tps(),
			onlinePlayers,
			Math.max(onlinePlayers, probe.maxPlayers()),
			probe.motd(),
			probe.whitelistEnforced(),
			sparkMetrics.systemCpuUsagePercent(),
			sparkMetrics.processCpuUsagePercent(),
			ramUsedBytes,
			ramFreeBytes,
			ramMaxBytes,
			0L,
			probe.worlds(),
			tickRecorder.snapshot()
		);
	}

	private String resolveServerName() {
		String configured = config.serverName();
		if (configured != null && !configured.isBlank()) {
			return configured;
		}

		int port = probe.port();
		return "127.0.0.1:" + (port > 0 ? port : 25565);
	}

	private double currentSystemCpuUsagePercent() {
		Object bean = ManagementFactory.getOperatingSystemMXBean();
		for (String methodName : new String[] {"getCpuLoad", "getSystemCpuLoad"}) {
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
			if (value instanceof Number number && number.doubleValue() >= 0D) {
				return normalizeCpuPercent(number.doubleValue());
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
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
}
