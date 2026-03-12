package dev.belikhun.luna.core.paper.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

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
	}

	public void start() {
		stop();
		if (!configStore.get("heartbeat.enabled").asBoolean(false)) {
			logger.debug("Heartbeat backend->velocity đang tắt trong cấu hình.");
			return;
		}

		String endpoint = configStore.get("heartbeat.endpoint").asString("http://127.0.0.1:32452/api/heartbeat").trim();
		String serverName = configuredServerName();
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
		taskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> postHeartbeat(uri, secret, readTimeoutMillis), 20L, intervalTicks).getTaskId();
		logger.success("Đã bật heartbeat backend tới Velocity endpoint=" + uri);
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
			BackendHeartbeatStats stats = collectStats();
			Map<String, String> bodyFields = HeartbeatFormCodec.encodeStats(stats);
			bodyFields.put("online", String.valueOf(online));
			String body = HeartbeatFormCodec.encodeToString(bodyFields);

			HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
				.header("X-Luna-Forwarding-Secret", secret)
				.timeout(Duration.ofMillis(readTimeoutMillis))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				logger.warn("Heartbeat nhận statusCode=" + response.statusCode() + " online=" + online);
				return;
			}

			statusView.updateSnapshot(HeartbeatFormCodec.decodeSnapshot(response.body()));
		} catch (Exception exception) {
			logger.debug("Heartbeat lỗi online=" + online + ": " + exception.getMessage());
		}
	}

	private BackendHeartbeatStats collectStats() {
		long now = System.currentTimeMillis();
		long uptimeMillis = Math.max(0L, now - bootEpochMillis);

		double tps = 0D;
		try {
			double[] values = Bukkit.getTPS();
			if (values.length > 0) {
				tps = values[0];
			}
		} catch (Throwable ignored) {
		}

		Runtime runtime = Runtime.getRuntime();
		long used = runtime.totalMemory() - runtime.freeMemory();

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
			used,
			runtime.freeMemory(),
			runtime.maxMemory()
		);
	}

	private String configuredServerName() {
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
