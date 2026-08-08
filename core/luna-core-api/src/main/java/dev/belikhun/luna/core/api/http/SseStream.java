package dev.belikhun.luna.core.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One server-sent-events connection.
 *
 * Frames are flushed as they are written, so a subscriber sees an event the moment
 * it is broadcast. Every write is synchronized because the initial snapshot is
 * written on the request thread while later events come from the broadcaster's
 * thread. The first write failure marks the stream closed for good — a dead
 * subscriber must never be retried on every subsequent broadcast.
 */
public final class SseStream implements AutoCloseable {
	private final OutputStream out;
	private final Runnable onClose;
	private volatile boolean closed;

	public SseStream(OutputStream out, Runnable onClose) {
		this.out = out;
		this.onClose = onClose;
	}

	/** Whether this stream has been closed or has failed a write. */
	public boolean closed() {
		return closed;
	}

	/**
	 * Send one event. Multi-line payloads are split across `data:` lines, as the
	 * SSE framing requires.
	 *
	 * @return false when the subscriber has gone away
	 */
	public synchronized boolean event(String name, String data) {
		if (closed) {
			return false;
		}

		StringBuilder frame = new StringBuilder();
		if (name != null && !name.isBlank()) {
			frame.append("event: ").append(name).append('\n');
		}

		String payload = data == null ? "" : data;
		for (String line : payload.split("\n", -1)) {
			frame.append("data: ").append(line).append('\n');
		}
		frame.append('\n');

		return write(frame.toString());
	}

	/** Send a comment line, used as a keep-alive that clients ignore. */
	public synchronized boolean comment(String text) {
		if (closed) {
			return false;
		}

		return write(": " + (text == null ? "" : text) + "\n\n");
	}

	/** Tell the client how long to wait before reconnecting. */
	public synchronized boolean retry(long millis) {
		if (closed) {
			return false;
		}

		return write("retry: " + Math.max(0L, millis) + "\n\n");
	}

	private boolean write(String frame) {
		try {
			out.write(frame.getBytes(StandardCharsets.UTF_8));
			out.flush();

			return true;
		} catch (IOException exception) {
			closed = true;
			release();

			return false;
		}
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}

		closed = true;
		release();
	}

	private void release() {
		try {
			out.close();
		} catch (IOException ignored) {
			// the peer is already gone — nothing left to report
		}

		if (onClose != null) {
			onClose.run();
		}
	}
}
