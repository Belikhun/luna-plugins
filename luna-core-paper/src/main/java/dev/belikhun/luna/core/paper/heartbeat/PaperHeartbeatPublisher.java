package dev.belikhun.luna.core.paper.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;

public final class PaperHeartbeatPublisher {
	private final Plugin plugin;
	private final ConfigStore configStore;
	private final LunaLogger logger;
	private final PaperBackendStatusView statusView;
	private final long bootEpochMillis;
	private int taskId;
	private HttpClient client;
	private URI heartbeatUri;
	private String heartbeatSecret;
	private int heartbeatReadTimeoutMillis;
	private volatile int lastReportedPlayerCount;
	private volatile long lastSnapshotRevision;
	private volatile boolean diagnosticsEnabled;
	private volatile long revisionLagWarnThreshold;
	private volatile long responseWarnThresholdMs;
	private volatile boolean sparkProbeWarned;
	private volatile boolean transportLoggingEnabled;
	private volatile Consumer<byte[]> selectorPayloadConsumer;
	private volatile Consumer<byte[]> messagingConfigConsumer;
	private volatile long selectorPayloadChecksum;
	private volatile long messagingConfigChecksum;

	public PaperHeartbeatPublisher(Plugin plugin, ConfigStore configStore, LunaLogger logger, PaperBackendStatusView statusView) {
		this.plugin = plugin;
		this.configStore = configStore;
		this.logger = logger.scope("Heartbeat");
		this.statusView = statusView;
		this.bootEpochMillis = System.currentTimeMillis();
		this.taskId = -1;
		this.heartbeatUri = null;
		this.heartbeatSecret = "";
		this.heartbeatReadTimeoutMillis = 3000;
		this.lastReportedPlayerCount = -1;
		this.lastSnapshotRevision = -1L;
		this.diagnosticsEnabled = true;
		this.revisionLagWarnThreshold = 25L;
		this.responseWarnThresholdMs = 250L;
		this.sparkProbeWarned = false;
		this.transportLoggingEnabled = false;
		this.selectorPayloadConsumer = null;
		this.messagingConfigConsumer = null;
		this.selectorPayloadChecksum = 0L;
		this.messagingConfigChecksum = 0L;
	}

	public void setSelectorPayloadConsumer(Consumer<byte[]> consumer) {
		this.selectorPayloadConsumer = consumer;
	}

	public void setMessagingConfigConsumer(Consumer<byte[]> consumer) {
		this.messagingConfigConsumer = consumer;
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

		client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMillis)).build();
		heartbeatUri = uri;
		heartbeatSecret = secret;
		heartbeatReadTimeoutMillis = readTimeoutMillis;
		lastReportedPlayerCount = Bukkit.getOnlinePlayers().size();
		lastSnapshotRevision = -1L;
		diagnosticsEnabled = configStore.get("diagnostics.heartbeat.enabled").asBoolean(true);
		revisionLagWarnThreshold = Math.max(0L, configStore.get("diagnostics.heartbeat.revisionLagWarnThreshold").asLong(25L));
		responseWarnThresholdMs = Math.max(1L, configStore.get("diagnostics.heartbeat.responseWarnThresholdMs").asLong(250L));
		transportLoggingEnabled = configStore.get("logging.heartbeatTransport.enabled").asBoolean(false);
		selectorPayloadChecksum = 0L;
		messagingConfigChecksum = 0L;
		syncServerSelectorConfigNow();
		syncMessagingConfigNow();
		taskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> postHeartbeat(uri, secret, readTimeoutMillis), 20L, intervalTicks).getTaskId();
		logger.success("Đã bật heartbeat backend tới Velocity endpoint=" + uri);
	}

	public void syncServerSelectorConfigNow() {
		fetchServerSelectorConfig(heartbeatUri, heartbeatSecret, heartbeatReadTimeoutMillis);
	}

	public void syncMessagingConfigNow() {
		fetchMessagingConfig(heartbeatUri, heartbeatSecret, heartbeatReadTimeoutMillis);
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
			postHeartbeat(heartbeatUri, heartbeatSecret, heartbeatReadTimeoutMillis, false);
		}
	}

	private void postHeartbeat(URI uri, String secret, int readTimeoutMillis) {
		postHeartbeat(uri, secret, readTimeoutMillis, true);
	}

	private void postHeartbeat(URI uri, String secret, int readTimeoutMillis, boolean online) {
		if (uri == null || secret == null || secret.isBlank()) {
			return;
		}

		try {
			long startedAt = System.currentTimeMillis();
			URI requestUri = withSinceQuery(uri, lastSnapshotRevision);
			BackendHeartbeatStats stats = collectStats();
			lastReportedPlayerCount = stats.onlinePlayers();
			Map<String, String> bodyFields = HeartbeatFormCodec.encodeStats(stats);
			bodyFields.put("online", String.valueOf(online));
			bodyFields.put("clientSentEpochMillis", String.valueOf(startedAt));
			String body = HeartbeatFormCodec.encodeToString(bodyFields);
			transportLog("[TX] POST " + requestUri + " online=" + online + " since=" + lastSnapshotRevision + " body=" + body);

			HttpRequest request = HttpRequest.newBuilder(requestUri)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
				.header("X-Luna-Forwarding-Secret", secret)
				.timeout(Duration.ofMillis(readTimeoutMillis))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			transportLog("[RX] POST " + requestUri + " status=" + response.statusCode() + " body=" + formatFormBody(response.body()));
			if (response.statusCode() != 200) {
				logger.warn("Heartbeat nhận statusCode=" + response.statusCode() + " online=" + online);
				return;
			}

			HeartbeatFormCodec.HeartbeatSnapshotPayload payload = HeartbeatFormCodec.decodeSnapshotPayload(response.body());
			BackendMetadata previousMetadata = statusView.currentBackendMetadata().orElse(null);
			if (payload.fullSync()) {
				statusView.updateSnapshot(payload);
			} else {
				statusView.applyDelta(payload);
			}
			logCurrentBackendMetadataChange(previousMetadata, statusView.currentBackendMetadata().orElse(null));

			long revisionLag = computeRevisionLag(payload.revision(), lastSnapshotRevision);
			long responseMs = Math.max(0L, System.currentTimeMillis() - startedAt);
			if (diagnosticsEnabled && revisionLag > revisionLagWarnThreshold) {
				logger.warn("Heartbeat diagnostics: revision lag=" + revisionLag + " (local=" + lastSnapshotRevision + ", remote=" + payload.revision() + ")");
			}
			if (diagnosticsEnabled && responseMs > responseWarnThresholdMs) {
				logger.warn("Heartbeat diagnostics: slow response " + responseMs + "ms, rows=" + payload.statuses().size() + ", fullSync=" + payload.fullSync());
			}

			lastSnapshotRevision = Math.max(lastSnapshotRevision, payload.revision());
			fetchMessagingConfig(uri, secret, readTimeoutMillis);
		} catch (Exception exception) {
			logger.debug("Heartbeat lỗi online=" + online + ": " + exception.getMessage());
		}
	}

	private void logCurrentBackendMetadataChange(BackendMetadata previous, BackendMetadata current) {
		if (sameMetadata(previous, current)) {
			return;
		}

		if (current == null || current.isBlank()) {
			if (previous != null && !previous.isBlank()) {
				logger.warn("Heartbeat đã mất BackendMetadata hiện tại của backend.");
			}
			return;
		}

		String summary = "name=" + current.name()
			+ " display=" + current.displayName()
			+ " accent=" + (current.accentColor().isBlank() ? "<empty>" : current.accentColor());
		if (previous == null || previous.isBlank()) {
			logger.success("Heartbeat đã resolve BackendMetadata hiện tại: " + summary);
			return;
		}

		logger.audit("Heartbeat đã cập nhật BackendMetadata hiện tại: " + summary);
	}

	private boolean sameMetadata(BackendMetadata left, BackendMetadata right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return left.sanitize().equals(right.sanitize());
	}

	private void fetchServerSelectorConfig(URI heartbeatRequestUri, String secret, int readTimeoutMillis) {
		Consumer<byte[]> consumer = selectorPayloadConsumer;
		if (consumer == null || heartbeatRequestUri == null || secret == null || secret.isBlank()) {
			return;
		}

		try {
			URI selectorUri = selectorConfigUri(heartbeatRequestUri);
			if (selectorUri == null) {
				return;
			}
			transportLog("[TX] GET " + selectorUri + " kind=server-selector-config");
			HttpRequest request = HttpRequest.newBuilder(selectorUri)
				.header("X-Luna-Forwarding-Secret", secret)
				.timeout(Duration.ofMillis(readTimeoutMillis))
				.GET()
				.build();

			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			transportLog("[RX] GET " + selectorUri + " status=" + response.statusCode() + " kind=server-selector-config body=" + formatBinaryBody(response.body()));
			if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
				return;
			}

			long checksum = checksum(response.body());
			if (checksum == selectorPayloadChecksum) {
				return;
			}

			selectorPayloadChecksum = checksum;
			consumer.accept(response.body());
		} catch (Exception exception) {
			logger.debug("Heartbeat selector sync lỗi: " + exception.getMessage());
		}
	}

	private void fetchMessagingConfig(URI heartbeatRequestUri, String secret, int readTimeoutMillis) {
		Consumer<byte[]> consumer = messagingConfigConsumer;
		if (consumer == null || heartbeatRequestUri == null || secret == null || secret.isBlank()) {
			return;
		}

		try {
			URI configUri = messagingConfigUri(heartbeatRequestUri);
			if (configUri == null) {
				return;
			}
			transportLog("[TX] GET " + configUri + " kind=messaging-config");

			HttpRequest request = HttpRequest.newBuilder(configUri)
				.header("X-Luna-Forwarding-Secret", secret)
				.timeout(Duration.ofMillis(readTimeoutMillis))
				.GET()
				.build();

			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			transportLog("[RX] GET " + configUri + " status=" + response.statusCode() + " kind=messaging-config body=" + formatFormBody(response.body()));
			if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
				return;
			}

			long checksum = checksum(response.body());
			if (checksum == messagingConfigChecksum) {
				return;
			}

			messagingConfigChecksum = checksum;
			consumer.accept(response.body());
		} catch (Exception exception) {
			logger.debug("Heartbeat messaging config sync lỗi: " + exception.getMessage());
		}
	}

	private URI selectorConfigUri(URI heartbeatRequestUri) {
		return siblingConfigUri(heartbeatRequestUri, "/server-selector-config");
	}

	private URI messagingConfigUri(URI heartbeatRequestUri) {
		if (heartbeatRequestUri == null) {
			return null;
		}

		String path = heartbeatRequestUri.getPath();
		if (path == null || path.isBlank()) {
			return null;
		}

		int lastSlash = path.lastIndexOf('/');
		if (lastSlash < 0 || lastSlash >= path.length() - 1) {
			return siblingConfigUri(heartbeatRequestUri, "/messaging-config");
		}

		String encodedServer = path.substring(lastSlash + 1);
		return siblingConfigUri(heartbeatRequestUri, "/messaging-config/" + encodedServer);
	}

	private URI siblingConfigUri(URI heartbeatRequestUri, String endpointSuffix) {
		if (heartbeatRequestUri == null) {
			return null;
		}

		String path = heartbeatRequestUri.getPath();
		if (path == null || path.isBlank()) {
			return null;
		}

		int heartbeatMarker = path.indexOf("/heartbeat/");
		String siblingPath;
		if (heartbeatMarker >= 0) {
			String prefix = path.substring(0, heartbeatMarker);
			siblingPath = (prefix.isBlank() ? "" : prefix) + endpointSuffix;
		} else {
			int slashIndex = path.lastIndexOf('/');
			if (slashIndex < 0) {
				return null;
			}
			String prefix = path.substring(0, slashIndex);
			siblingPath = (prefix.isBlank() ? "" : prefix) + endpointSuffix;
		}

		return URI.create(heartbeatRequestUri.getScheme() + "://" + heartbeatRequestUri.getAuthority() + siblingPath);
	}

	private long checksum(byte[] bytes) {
		long hash = 1469598103934665603L;
		for (byte value : bytes) {
			hash ^= (value & 0xFFL);
			hash *= 1099511628211L;
		}
		return hash;
	}

	private long computeRevisionLag(long remoteRevision, long localRevision) {
		if (remoteRevision <= 0L || localRevision < 0L) {
			return 0L;
		}

		long lag = remoteRevision - localRevision - 1L;
		return Math.max(0L, lag);
	}

	private void publishNowAsync() {
		URI uri = heartbeatUri;
		String secret = heartbeatSecret;
		int readTimeoutMillis = heartbeatReadTimeoutMillis;
		if (uri == null || secret == null || secret.isBlank()) {
			return;
		}

		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> postHeartbeat(uri, secret, readTimeoutMillis));
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

	private URI withSinceQuery(URI baseUri, long sinceRevision) {
		if (baseUri == null) {
			return null;
		}

		String separator = baseUri.toString().contains("?") ? "&" : "?";
		return URI.create(baseUri + separator + "since=" + sinceRevision);
	}

	private void transportLog(String message) {
		if (!transportLoggingEnabled) {
			return;
		}
		logger.audit("Heartbeat transport: " + message);
	}

	private String formatFormBody(byte[] body) {
		if (body == null || body.length == 0) {
			return "<empty>";
		}
		return new String(body, StandardCharsets.UTF_8);
	}

	private String formatBinaryBody(byte[] body) {
		if (body == null || body.length == 0) {
			return "<empty>";
		}
		return "base64=" + Base64.getEncoder().encodeToString(body);
	}
}
