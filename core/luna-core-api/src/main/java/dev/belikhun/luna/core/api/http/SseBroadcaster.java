package dev.belikhun.luna.core.api.http;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Fan-out of server-sent events to every subscribed console client.
 *
 * Subscribers are registered from request threads and written to from whatever
 * thread produces an event (the heartbeat handler, a scheduler), so the list is
 * copy-on-write and each stream serializes its own writes. Streams that fail a
 * write are dropped on the spot rather than retried.
 *
 * A keep-alive comment goes out on a fixed interval: without traffic, an idle
 * event-stream connection is indistinguishable from a hung one to intermediaries
 * and to the client's own reconnect logic.
 */
public final class SseBroadcaster {
	private static final long KEEPALIVE_SECONDS = 20L;

	/** How long a client should wait before reconnecting after a drop. */
	private static final long RETRY_MILLIS = 3000L;

	private final LunaLogger logger;
	private final String name;
	private final List<SseStream> subscribers;
	private final ScheduledExecutorService keepAlive;

	public SseBroadcaster(LunaLogger logger, String name) {
		this.logger = logger.scope("Sse");
		this.name = name;
		this.subscribers = new CopyOnWriteArrayList<>();

		this.keepAlive = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-sse-keepalive-" + name);
			thread.setDaemon(true);
			return thread;
		});

		this.keepAlive.scheduleAtFixedRate(this::sweep, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
	}

	/** Number of connected subscribers. */
	public int size() {
		return subscribers.size();
	}

	/**
	 * Build the response for a new subscriber.
	 *
	 * @param onOpen called with the fresh stream so the endpoint can send an initial
	 *               snapshot before any incremental event arrives
	 */
	public HttpResponse subscribe(Consumer<SseStream> onOpen) {
		return HttpResponse.sse(stream -> {
			stream.retry(RETRY_MILLIS);

			if (onOpen != null) {
				onOpen.accept(stream);
			}

			if (stream.closed()) {
				return;
			}

			subscribers.add(stream);
			logger.debug("Subscriber mới cho " + name + " (tổng " + subscribers.size() + ")");
		});
	}

	/** Write one event to every subscriber, dropping the ones that have gone away. */
	public void broadcast(String event, String data) {
		if (subscribers.isEmpty()) {
			return;
		}

		for (SseStream stream : subscribers) {
			if (!stream.event(event, data)) {
				drop(stream);
			}
		}
	}

	/** Close every subscriber — used on plugin reload and shutdown. */
	public void close() {
		keepAlive.shutdownNow();

		for (SseStream stream : subscribers) {
			stream.close();
		}

		subscribers.clear();
	}

	private void sweep() {
		for (SseStream stream : subscribers) {
			if (!stream.comment("keep-alive")) {
				drop(stream);
			}
		}
	}

	private void drop(SseStream stream) {
		stream.close();

		if (subscribers.remove(stream)) {
			logger.debug("Subscriber của " + name + " đã ngắt (còn " + subscribers.size() + ")");
		}
	}
}
