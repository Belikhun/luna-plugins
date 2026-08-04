package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A backend's whole conversation with the proxy registry: the heartbeat it
 * publishes, the rows it gets back, the live stream it listens on, and the
 * selector/messaging configuration it pulls.
 *
 * Paper and NeoForge used to carry a copy of this each, which is how they drifted
 * apart — one re-fetched the selector configuration on every beat and the other
 * only at startup, so the same edit reached one platform and not the other.
 *
 * Two channels feed the same {@link BackendStatusStore}:
 *
 *  - the **heartbeat**, which publishes this server's own stats and returns every
 *    row written since the cursor. It is the safety net: whatever the stream
 *    missed comes back here.
 *  - the **stream**, which pushes a row the moment it changes and needs no player
 *    online to arrive.
 */
public final class BackendRegistryClient {
	private static final long RECONNECT_MIN_MILLIS = 1000L;
	private static final long RECONNECT_MAX_MILLIS = 15_000L;

	private final LunaLogger logger;
	private final BackendStatusStore store;

	private volatile HttpClient client;
	private volatile URI heartbeatUri;
	private volatile String secret;
	private volatile int readTimeoutMillis;
	private volatile boolean transportLoggingEnabled;
	private volatile boolean streamEnabled;
	private volatile Supplier<BackendHeartbeatStats> statsSupplier;
	private volatile Consumer<byte[]> selectorPayloadConsumer;
	private volatile Consumer<byte[]> messagingConfigConsumer;
	private volatile long selectorPayloadChecksum;
	private volatile long messagingConfigChecksum;
	private volatile Thread streamThread;
	private volatile boolean streamLive;
	private volatile boolean running;

	public BackendRegistryClient(LunaLogger logger, BackendStatusStore store) {
		this.logger = logger.scope("Registry");
		this.store = store;
		this.secret = "";
		this.readTimeoutMillis = 3000;
		this.transportLoggingEnabled = false;
		this.streamEnabled = true;
		this.selectorPayloadChecksum = 0L;
		this.messagingConfigChecksum = 0L;
		this.streamLive = false;
		this.running = false;
	}

	public BackendStatusStore store() {
		return store;
	}

	/** Whether the push stream is currently connected. */
	public boolean streamLive() {
		return streamLive;
	}

	public void setSelectorPayloadConsumer(Consumer<byte[]> consumer) {
		this.selectorPayloadConsumer = consumer;
	}

	public void setMessagingConfigConsumer(Consumer<byte[]> consumer) {
		this.messagingConfigConsumer = consumer;
	}

	/**
	 * Point the client at a proxy and start listening.
	 *
	 * @param heartbeatUri  the backend's own heartbeat endpoint, siblings are derived from it
	 * @param statsSupplier collects this server's stats; called on the publishing thread
	 */
	public void start(
		URI heartbeatUri,
		String secret,
		int connectTimeoutMillis,
		int readTimeoutMillis,
		boolean streamEnabled,
		boolean transportLoggingEnabled,
		Supplier<BackendHeartbeatStats> statsSupplier
	) {
		stop();

		this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMillis))).build();
		this.heartbeatUri = heartbeatUri;
		this.secret = secret == null ? "" : secret;
		this.readTimeoutMillis = Math.max(500, readTimeoutMillis);
		this.streamEnabled = streamEnabled;
		this.transportLoggingEnabled = transportLoggingEnabled;
		this.statsSupplier = statsSupplier;
		this.selectorPayloadChecksum = 0L;
		this.messagingConfigChecksum = 0L;
		this.running = true;

		store.resetCursor();

		if (!streamEnabled) {
			logger.warn("Registry stream đang tắt: trạng thái backend sẽ chỉ cập nhật theo nhịp heartbeat.");
			return;
		}

		Thread thread = new Thread(this::runStream, "luna-registry-stream");
		thread.setDaemon(true);
		this.streamThread = thread;
		thread.start();
	}

	public void stop() {
		running = false;
		streamLive = false;

		Thread thread = streamThread;
		streamThread = null;
		if (thread != null) {
			thread.interrupt();
		}
	}

	/**
	 * Publish this server's stats and merge whatever rows come back.
	 *
	 * @param online false marks a graceful shutdown, so the proxy does not wait
	 *               out the heartbeat timeout before flipping this server offline
	 * @return whether the exchange succeeded
	 */
	public boolean publish(boolean online) {
		URI uri = heartbeatUri;
		HttpClient httpClient = client;
		Supplier<BackendHeartbeatStats> supplier = statsSupplier;
		if (uri == null || httpClient == null || supplier == null || secret.isBlank()) {
			return false;
		}

		try {
			long startedAt = System.currentTimeMillis();
			long cursor = store.pollCursor();
			URI requestUri = withCursor(uri, cursor, store.epoch());
			BackendHeartbeatStats stats = supplier.get();
			Map<String, String> bodyFields = HeartbeatFormCodec.encodeStats(stats);
			bodyFields.put("online", String.valueOf(online));
			bodyFields.put("clientSentEpochMillis", String.valueOf(startedAt));
			String body = HeartbeatFormCodec.encodeToString(bodyFields);
			transportLog("[TX] POST " + requestUri + " online=" + online + " since=" + cursor + " body=" + body);

			HttpRequest request = HttpRequest.newBuilder(requestUri)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
				.header("X-Luna-Forwarding-Secret", secret)
				.timeout(Duration.ofMillis(readTimeoutMillis))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

			HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
			transportLog("[RX] POST " + requestUri + " status=" + response.statusCode() + " body=" + formatFormBody(response.body()));
			if (response.statusCode() != 200) {
				logger.warn("Heartbeat nhận statusCode=" + response.statusCode() + " online=" + online);
				return false;
			}

			store.apply(HeartbeatFormCodec.decodeSnapshotPayload(response.body()), true);

			// the messaging config can change under a proxy reload just like the
			// selector one, and it is cheap: the checksum drops an identical body
			fetchMessagingConfig();
			return true;
		} catch (Exception exception) {
			logger.debug("Heartbeat lỗi online=" + online + ": " + exception.getMessage());
			return false;
		}
	}

	/** Pull the selector configuration, applying it only when it actually changed. */
	public void syncSelectorConfigNow() {
		fetchConfig(
			siblingUri("/server-selector-config"),
			"server-selector-config",
			selectorPayloadConsumer,
			checksum -> selectorPayloadChecksum = checksum,
			() -> selectorPayloadChecksum,
			true
		);
	}

	public void syncMessagingConfigNow() {
		fetchMessagingConfig();
	}

	private void fetchMessagingConfig() {
		fetchConfig(
			messagingConfigUri(),
			"messaging-config",
			messagingConfigConsumer,
			checksum -> messagingConfigChecksum = checksum,
			() -> messagingConfigChecksum,
			false
		);
	}

	private void runStream() {
		long backoffMillis = RECONNECT_MIN_MILLIS;

		while (running) {
			try {
				URI streamUri = siblingUri("/heartbeat/stream");
				HttpClient httpClient = client;
				if (streamUri == null || httpClient == null || secret.isBlank()) {
					return;
				}

				// no request timeout: the whole point of the stream is that it stays
				// open with nothing on it until something changes
				HttpRequest request = HttpRequest.newBuilder(streamUri)
					.header("X-Luna-Forwarding-Secret", secret)
					.header("Accept", "text/event-stream")
					.GET()
					.build();

				HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					throw new IllegalStateException("stream trả về status=" + response.statusCode());
				}

				streamLive = true;
				backoffMillis = RECONNECT_MIN_MILLIS;
				logger.success("Đã kết nối registry stream: " + streamUri);

				// a reconnect may have spanned a proxy reload, so the selector layout
				// this backend holds can be older than the proxy's
				syncSelectorConfigNow();

				consumeStream(response.body());
			} catch (Exception exception) {
				if (running) {
					logger.debug("Registry stream ngắt: " + exception.getMessage());
				}
			}

			streamLive = false;
			if (!running) {
				return;
			}

			try {
				Thread.sleep(backoffMillis);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}

			backoffMillis = Math.min(RECONNECT_MAX_MILLIS, backoffMillis * 2L);
		}
	}

	private void consumeStream(InputStream body) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
			String event = "";
			StringBuilder data = new StringBuilder();
			String line;

			while (running && (line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					dispatchStreamEvent(event, data.toString());
					event = "";
					data.setLength(0);
					continue;
				}

				if (line.startsWith(":")) {
					continue;
				}

				if (line.startsWith("event:")) {
					event = line.substring("event:".length()).trim();
					continue;
				}

				if (line.startsWith("data:")) {
					if (data.length() > 0) {
						data.append('\n');
					}
					data.append(line.substring("data:".length()).trim());
				}
			}
		}
	}

	private void dispatchStreamEvent(String event, String data) {
		if (data.isEmpty()) {
			return;
		}

		if ("row".equals(event) || "snapshot".equals(event)) {
			transportLog("[RX] stream event=" + event + " body=" + data);
			// the cursor stays where the heartbeat left it: this event's revision
			// says nothing about rows that changed before it and are still owed
			store.apply(HeartbeatFormCodec.decodeSnapshotPayload(data.getBytes(StandardCharsets.UTF_8)), false);
			return;
		}

		if ("config".equals(event)) {
			transportLog("[RX] stream event=config");
			syncSelectorConfigNow();
		}
	}

	private void fetchConfig(
		URI uri,
		String kind,
		Consumer<byte[]> consumer,
		Consumer<Long> storeChecksum,
		Supplier<Long> readChecksum,
		boolean binary
	) {
		HttpClient httpClient = client;
		if (consumer == null || uri == null || httpClient == null || secret.isBlank()) {
			return;
		}

		try {
			transportLog("[TX] GET " + uri + " kind=" + kind);
			HttpRequest request = HttpRequest.newBuilder(uri)
				.header("X-Luna-Forwarding-Secret", secret)
				.timeout(Duration.ofMillis(readTimeoutMillis))
				.GET()
				.build();

			HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
			transportLog("[RX] GET " + uri + " status=" + response.statusCode() + " kind=" + kind
				+ " body=" + (binary ? formatBinaryBody(response.body()) : formatFormBody(response.body())));
			if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
				return;
			}

			long checksum = checksum(response.body());
			if (checksum == readChecksum.get()) {
				return;
			}

			storeChecksum.accept(checksum);
			consumer.accept(response.body());
		} catch (Exception exception) {
			logger.debug("Đồng bộ " + kind + " lỗi: " + exception.getMessage());
		}
	}

	private URI messagingConfigUri() {
		URI uri = heartbeatUri;
		if (uri == null) {
			return null;
		}

		String path = uri.getPath();
		if (path == null || path.isBlank()) {
			return null;
		}

		int lastSlash = path.lastIndexOf('/');
		if (lastSlash < 0 || lastSlash >= path.length() - 1) {
			return siblingUri("/messaging-config");
		}

		return siblingUri("/messaging-config/" + path.substring(lastSlash + 1));
	}

	/**
	 * Derive a sibling endpoint from the heartbeat URI, so a backend only ever
	 * configures one address.
	 */
	private URI siblingUri(String endpointSuffix) {
		URI uri = heartbeatUri;
		if (uri == null) {
			return null;
		}

		String path = uri.getPath();
		if (path == null || path.isBlank()) {
			return null;
		}

		int heartbeatMarker = path.indexOf("/heartbeat/");
		String siblingPath;
		if (heartbeatMarker >= 0) {
			siblingPath = path.substring(0, heartbeatMarker) + endpointSuffix;
		} else {
			int slashIndex = path.lastIndexOf('/');
			if (slashIndex < 0) {
				return null;
			}
			siblingPath = path.substring(0, slashIndex) + endpointSuffix;
		}

		return URI.create(uri.getScheme() + "://" + uri.getAuthority() + siblingPath);
	}

	private URI withCursor(URI baseUri, long sinceRevision, String epoch) {
		String separator = baseUri.toString().contains("?") ? "&" : "?";
		String query = separator + "since=" + sinceRevision;
		if (epoch != null && !epoch.isBlank()) {
			query = query + "&epoch=" + epoch;
		}
		return URI.create(baseUri + query);
	}

	private long checksum(byte[] bytes) {
		long hash = 1469598103934665603L;
		for (byte value : bytes) {
			hash ^= (value & 0xFFL);
			hash *= 1099511628211L;
		}
		return hash;
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
