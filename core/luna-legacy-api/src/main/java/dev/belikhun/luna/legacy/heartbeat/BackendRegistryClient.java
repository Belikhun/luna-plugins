package dev.belikhun.luna.legacy.heartbeat;

import dev.belikhun.luna.legacy.http.LegacyHttp;
import dev.belikhun.luna.legacy.http.ProxyEndpoints;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A backend's whole conversation with the proxy registry: the heartbeat it publishes,
 * the rows it gets back, the live stream it listens on, and the selector/messaging
 * configuration it pulls.
 *
 * Two channels feed the same {@link BackendStatusStore}:
 *
 *  - the **heartbeat**, which publishes this server's own stats and returns every row
 *    written since the cursor. It is the safety net: whatever the stream missed comes
 *    back here.
 *  - the **stream**, which pushes a row the moment it changes and needs no player
 *    online to arrive.
 *
 * The transport is `HttpURLConnection` rather than the modern api's `HttpClient`,
 * which is Java 11. That changes one thing beyond plumbing: `HttpClient` carries its
 * own connect timeout, so this class has to hold that value itself and pass it per
 * request.
 */
public final class BackendRegistryClient {
	private static final long RECONNECT_MIN_MILLIS = 1000L;
	private static final long RECONNECT_MAX_MILLIS = 15_000L;

	private static final String SECRET_HEADER = "X-Luna-Forwarding-Secret";

	private final LunaLogger logger;
	private final BackendStatusStore store;

	private volatile URI heartbeatUri;
	private volatile String secret;
	private volatile int connectTimeoutMillis;
	private volatile int readTimeoutMillis;
	private volatile boolean transportLoggingEnabled;
	private volatile boolean streamEnabled;
	private volatile Supplier<BackendHeartbeatStats> statsSupplier;
	private volatile Consumer<byte[]> selectorPayloadConsumer;
	private volatile Consumer<byte[]> messagingConfigConsumer;
	private volatile long selectorPayloadChecksum;
	private volatile long messagingConfigChecksum;
	private volatile Thread streamThread;
	private volatile HttpURLConnection streamConnection;
	private volatile boolean streamLive;
	private volatile boolean running;

	public BackendRegistryClient(LunaLogger logger, BackendStatusStore store) {
		this.logger = logger.scope("Registry");
		this.store = store;
		this.secret = "";
		this.connectTimeoutMillis = 3000;
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
		this.selectorPayloadChecksum = 0L;
	}

	/**
	 * A consumer wired after the first fetch would otherwise never see a body: the
	 * checksum already matches what the proxy is serving and every later fetch returns
	 * early. Clearing it makes the next sync deliver.
	 *
	 * That is not hypothetical on the mod loaders, where the messaging bus is a
	 * separate mod loading after the core that owns this client.
	 */
	public void setMessagingConfigConsumer(Consumer<byte[]> consumer) {
		this.messagingConfigConsumer = consumer;
		this.messagingConfigChecksum = 0L;
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

		this.heartbeatUri = heartbeatUri;
		this.secret = secret == null ? "" : secret;
		this.connectTimeoutMillis = Math.max(500, connectTimeoutMillis);
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

		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				runStream();
			}
		}, "luna-registry-stream");

		thread.setDaemon(true);
		this.streamThread = thread;
		thread.start();
	}

	public void stop() {
		running = false;
		streamLive = false;

		// interrupting a thread blocked in a socket read does nothing on its own, so
		// the connection is torn down too; without this a stop waits out the proxy
		HttpURLConnection connection = streamConnection;
		streamConnection = null;

		if (connection != null) {
			connection.disconnect();
		}

		Thread thread = streamThread;
		streamThread = null;

		if (thread != null) {
			thread.interrupt();
		}
	}

	/**
	 * Publish this server's stats and merge whatever rows come back.
	 *
	 * @param online false marks a graceful shutdown, so the proxy does not wait out
	 *               the heartbeat timeout before flipping this server offline
	 * @return whether the exchange succeeded
	 */
	public boolean publish(boolean online) {
		URI uri = heartbeatUri;
		Supplier<BackendHeartbeatStats> supplier = statsSupplier;

		if (uri == null || supplier == null || Strings.isBlank(secret)) {
			return false;
		}

		try {
			long startedAt = System.currentTimeMillis();
			long cursor = store.pollCursor();
			URI requestUri = withCursor(uri, cursor, store.epoch());

			Map<String, String> bodyFields = HeartbeatFormCodec.encodeStats(supplier.get());

			bodyFields.put("online", String.valueOf(online));
			bodyFields.put("clientSentEpochMillis", String.valueOf(startedAt));

			String body = HeartbeatFormCodec.encodeToString(bodyFields);

			transportLog("[TX] POST " + requestUri + " online=" + online + " since=" + cursor + " body=" + body);

			Map<String, String> headers = headers();

			headers.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

			LegacyHttp.Response response = LegacyHttp.post(
				requestUri,
				headers,
				body.getBytes(StandardCharsets.UTF_8),
				connectTimeoutMillis,
				readTimeoutMillis
			);

			transportLog("[RX] POST " + requestUri + " status=" + response.status()
				+ " body=" + formatFormBody(response.body()));

			if (!response.ok()) {
				logger.warn("Heartbeat nhận statusCode=" + response.status() + " online=" + online);

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

				if (streamUri == null || Strings.isBlank(secret)) {
					return;
				}

				Map<String, String> headers = headers();

				headers.put("Accept", "text/event-stream");

				// no read timeout: the whole point of the stream is that it stays open
				// with nothing on it until something changes
				HttpURLConnection connection = LegacyHttp.openEventStream(streamUri, headers, connectTimeoutMillis);

				streamConnection = connection;

				try {
					int status = connection.getResponseCode();

					if (status != HttpURLConnection.HTTP_OK) {
						throw new IllegalStateException("stream trả về status=" + status);
					}

					streamLive = true;
					backoffMillis = RECONNECT_MIN_MILLIS;
					logger.success("Đã kết nối registry stream: " + streamUri);

					// a reconnect may have spanned a proxy reload, so the selector layout
					// this backend holds can be older than the proxy's
					syncSelectorConfigNow();

					consumeStream(connection.getInputStream());
				} finally {
					streamConnection = null;
					connection.disconnect();
				}
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
		BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));

		try {
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
		} finally {
			reader.close();
		}
	}

	private void dispatchStreamEvent(String event, String data) {
		if (data.isEmpty()) {
			return;
		}

		if ("row".equals(event) || "snapshot".equals(event)) {
			transportLog("[RX] stream event=" + event + " body=" + data);

			// the cursor stays where the heartbeat left it: this event's revision says
			// nothing about rows that changed before it and are still owed
			store.apply(HeartbeatFormCodec.decodeSnapshotPayload(data.getBytes(StandardCharsets.UTF_8)), false);

			return;
		}

		if ("config".equals(event)) {
			transportLog("[RX] stream event=config");
			syncSelectorConfigNow();
		}
	}

	/**
	 * Fetch a config endpoint and hand its body to the right consumer, skipping a
	 * body identical to the one already applied.
	 *
	 * @param binary only changes how the body is rendered in the transport log; the
	 *               selector payload is a packed blob and printing it raw is noise
	 */
	private void fetchConfig(
		URI uri,
		String kind,
		Consumer<byte[]> consumer,
		Consumer<Long> storeChecksum,
		Supplier<Long> readChecksum,
		boolean binary
	) {
		if (consumer == null || uri == null || Strings.isBlank(secret)) {
			return;
		}

		try {
			transportLog("[TX] GET " + uri + " kind=" + kind);

			LegacyHttp.Response response = LegacyHttp.get(uri, headers(), connectTimeoutMillis, readTimeoutMillis);

			transportLog("[RX] GET " + uri + " status=" + response.status() + " kind=" + kind
				+ " body=" + (binary ? formatBinaryBody(response.body()) : formatFormBody(response.body())));

			if (!response.ok() || response.body() == null || response.body().length == 0) {
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

	private Map<String, String> headers() {
		Map<String, String> headers = new LinkedHashMap<String, String>();

		headers.put(SECRET_HEADER, secret);

		return headers;
	}

	private URI messagingConfigUri() {
		URI uri = heartbeatUri;

		if (uri == null) {
			return null;
		}

		String path = uri.getPath();

		if (Strings.isBlank(path)) {
			return null;
		}

		int lastSlash = path.lastIndexOf('/');

		if (lastSlash < 0 || lastSlash >= path.length() - 1) {
			return siblingUri("/messaging-config");
		}

		return siblingUri("/messaging-config/" + path.substring(lastSlash + 1));
	}

	private URI siblingUri(String endpointSuffix) {
		return ProxyEndpoints.sibling(heartbeatUri, endpointSuffix);
	}

	private URI withCursor(URI baseUri, long sinceRevision, String epoch) {
		String separator = baseUri.toString().contains("?") ? "&" : "?";
		String query = separator + "since=" + sinceRevision;

		if (Strings.hasText(epoch)) {
			query = query + "&epoch=" + epoch;
		}

		return URI.create(baseUri + query);
	}

	/** FNV-1a over the body, so an unchanged config costs no parsing. */
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
