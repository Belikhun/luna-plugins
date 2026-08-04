package dev.belikhun.luna.core.api.http;

/**
 * Callback that takes ownership of a long-lived response body.
 *
 * Implementations are expected to return promptly — send an initial snapshot,
 * register the stream with a broadcaster, and let later events be written from
 * whichever thread produces them. The request thread must not park here: the HTTP
 * server has a small handler pool, and a blocked handler stops serving everything
 * else.
 */
@FunctionalInterface
public interface ResponseStreamer {
	/**
	 * Called once, with the stream for this subscriber, after the response headers
	 * have been sent.
	 */
	void open(SseStream stream);
}
