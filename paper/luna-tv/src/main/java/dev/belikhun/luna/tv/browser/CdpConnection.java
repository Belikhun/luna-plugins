package dev.belikhun.luna.tv.browser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A Chrome DevTools Protocol connection over the JDK's own WebSocket client.
 *
 * Deliberately dependency-free: paper-api already brings Gson, and
 * java.net.http speaks WebSocket since 11, so a browser backend costs the
 * plugin no shaded libraries at all.
 *
 * CDP frames arrive split across WebSocket fragments once a screencast frame
 * grows past the transport's buffer, so text is accumulated until onText
 * reports the last fragment. Missing that is what makes a naive client throw
 * JsonSyntaxException on exactly the big frames it needs most.
 */
public final class CdpConnection implements AutoCloseable {

	private static final Gson GSON = new Gson();
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;
	private final WebSocket socket;
	private final AtomicInteger nextId = new AtomicInteger();
	private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final Map<String, Consumer<JsonObject>> listeners = new ConcurrentHashMap<>();
	private final StringBuilder inbound = new StringBuilder();

	/** Tail of the send chain; every outgoing frame is appended to it. */
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

	private volatile Consumer<Throwable> onFailure = throwable -> {};

	private CdpConnection(HttpClient http, String websocketUrl) throws Exception {
		this.http = http;
		this.socket = http.newWebSocketBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.buildAsync(URI.create(websocketUrl), new Listener())
			.get();
	}

	/**
	 * Opens a connection to one CDP websocket endpoint.
	 *
	 * @param websocketUrl the target's {@code webSocketDebuggerUrl}
	 * @return a live connection
	 * @throws Exception if the socket cannot be opened
	 */
	public static CdpConnection open(String websocketUrl) throws Exception {
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.build();

		return new CdpConnection(http, websocketUrl);
	}

	/** Called when the socket dies under us, so the owner can relaunch. */
	public void onFailure(Consumer<Throwable> handler) {
		this.onFailure = handler;
	}

	/**
	 * Subscribes to one CDP event.
	 *
	 * @param method the event name, e.g. {@code Page.screencastFrame}
	 * @param handler receives the event's params object
	 */
	public void on(String method, Consumer<JsonObject> handler) {
		listeners.put(method, handler);
	}

	/**
	 * Sends a command and forgets it.
	 *
	 * Used for the acknowledgements on the screencast hot path, where waiting
	 * for a reply would add a round trip per frame for nothing.
	 *
	 * @param method the CDP method
	 * @param params its parameters, may be empty
	 */
	public void send(String method, Map<String, Object> params) {
		int id = nextId.incrementAndGet();

		transmit(id, method, params);
	}

	/**
	 * Sends a command and completes when its reply arrives.
	 *
	 * @param method the CDP method
	 * @param params its parameters, may be empty
	 * @return the reply's {@code result} object, or an exceptionally completed
	 *         future when CDP answered with an error
	 */
	public CompletableFuture<JsonObject> call(String method, Map<String, Object> params) {
		int id = nextId.incrementAndGet();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		pending.put(id, future);
		transmit(id, method, params);

		return future;
	}

	private void transmit(int id, String method, Map<String, Object> params) {
		JsonObject frame = new JsonObject();

		frame.addProperty("id", id);
		frame.addProperty("method", method);
		frame.add("params", GSON.toJsonTree(params == null ? Map.of() : params));

		String text = GSON.toJson(frame);

		// The JDK WebSocket refuses a send while the previous one is still in
		// flight: the returned future completes exceptionally and the frame is
		// simply gone. A synchronized block alone does not help, because sendText
		// returns before the transfer finishes. Chaining every send behind the
		// last one is what actually serialises them - and losing a frame here is
		// not a small bug: a lost setDeviceMetricsOverride is a wrong-sized page,
		// a lost startScreencast is a screen that never draws at all.
		synchronized (this) {
			sendChain = sendChain
				.handle((unused, throwable) -> null)
				.thenCompose(unused -> socket.sendText(text, true));
		}
	}

	@Override
	public void close() {
		pending.values().forEach(future -> future.cancel(false));
		pending.clear();
		listeners.clear();

		try {
			socket.abort();
		} catch (Throwable ignored) {
			// aborting a already-dead socket is not worth reporting
		}
	}

	private void dispatch(String text) {
		JsonElement parsed = JsonParser.parseString(text);

		if (!parsed.isJsonObject()) {
			return;
		}

		JsonObject message = parsed.getAsJsonObject();

		if (message.has("id")) {
			CompletableFuture<JsonObject> future = pending.remove(message.get("id").getAsInt());

			if (future == null) {
				return;
			}

			if (message.has("error")) {
				future.completeExceptionally(new CdpException(message.getAsJsonObject("error").toString()));
				return;
			}

			JsonObject result = message.has("result") ? message.getAsJsonObject("result") : new JsonObject();
			future.complete(result);
			return;
		}

		if (!message.has("method")) {
			return;
		}

		Consumer<JsonObject> listener = listeners.get(message.get("method").getAsString());

		if (listener == null) {
			return;
		}

		JsonObject params = message.has("params") ? message.getAsJsonObject("params") : new JsonObject();
		listener.accept(params);
	}

	/** A CDP command that came back as an error rather than a result. */
	public static final class CdpException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		CdpException(String message) {
			super(message);
		}
	}

	private final class Listener implements WebSocket.Listener {

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			inbound.append(data);
			webSocket.request(1);

			if (!last) {
				return null;
			}

			String text = inbound.toString();
			inbound.setLength(0);

			try {
				dispatch(text);
			} catch (Throwable throwable) {
				onFailure.accept(throwable);
			}

			return null;
		}

		@Override
		public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			onFailure.accept(new IllegalStateException("CDP đã đóng kết nối: " + statusCode + " " + reason));

			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			onFailure.accept(error);
		}
	}
}
