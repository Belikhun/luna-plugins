package dev.belikhun.luna.legacy.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Map;

/**
 * The little bit of HTTP a 1.12.2 backend needs, over `HttpURLConnection`.
 *
 * `java.net.http.HttpClient` is Java 11, and it is the only thing in luna's
 * heartbeat path that a Java 8 runtime cannot provide. Rather than scatter
 * connection plumbing through every caller, it lives here: the heartbeat client and
 * the permission mirror both talk to the same proxy, with the same secret header and
 * the same timeouts.
 *
 * Three behaviours are deliberate and easy to lose:
 *
 * - **The error stream still has a body.** `getInputStream()` throws on a 4xx/5xx,
 *   and the body - which is where the proxy explains itself - is only on
 *   `getErrorStream()`. Reading just the happy path turns a useful 403 into an
 *   `IOException` with nothing in it.
 * - **A stream request must not have a read timeout.** An SSE connection is idle by
 *   design; a read timeout would tear it down on every quiet minute and make the
 *   backoff loop look like a flapping proxy.
 * - **No redirect following on the stream.** `HttpURLConnection` follows redirects by
 *   replaying the request, which for an event stream means silently reconnecting to
 *   somewhere the caller never approved.
 */
public final class LegacyHttp {
	private LegacyHttp() {
	}

	/** A completed request: the status line and the whole body, however it ended. */
	public static final class Response {
		private final int status;
		private final byte[] body;

		Response(int status, byte[] body) {
			this.status = status;
			this.body = body;
		}

		public int status() {
			return status;
		}

		public byte[] body() {
			return body;
		}

		public boolean ok() {
			return status == HttpURLConnection.HTTP_OK;
		}
	}

	public static Response get(URI uri, Map<String, String> headers, int connectTimeoutMillis, int readTimeoutMillis)
		throws IOException {
		return send(uri, "GET", headers, null, connectTimeoutMillis, readTimeoutMillis);
	}

	public static Response post(
		URI uri,
		Map<String, String> headers,
		byte[] body,
		int connectTimeoutMillis,
		int readTimeoutMillis
	) throws IOException {
		return send(uri, "POST", headers, body, connectTimeoutMillis, readTimeoutMillis);
	}

	private static Response send(
		URI uri,
		String method,
		Map<String, String> headers,
		byte[] body,
		int connectTimeoutMillis,
		int readTimeoutMillis
	) throws IOException {
		HttpURLConnection connection = open(uri, method, headers, connectTimeoutMillis);

		connection.setReadTimeout(Math.max(0, readTimeoutMillis));

		try {
			if (body != null) {
				connection.setDoOutput(true);
				connection.setFixedLengthStreamingMode(body.length);

				OutputStream output = connection.getOutputStream();

				try {
					output.write(body);
					output.flush();
				} finally {
					output.close();
				}
			}

			int status = connection.getResponseCode();

			return new Response(status, readBody(connection, status));
		} finally {
			connection.disconnect();
		}
	}

	/**
	 * Open a long-lived response stream, for server-sent events.
	 *
	 * The caller owns the returned connection and must `disconnect()` it; closing the
	 * body alone leaves the socket in the keep-alive pool waiting on a server that is
	 * never going to say anything more.
	 */
	public static HttpURLConnection openEventStream(URI uri, Map<String, String> headers, int connectTimeoutMillis)
		throws IOException {
		HttpURLConnection connection = open(uri, "GET", headers, connectTimeoutMillis);

		connection.setReadTimeout(0);
		connection.setInstanceFollowRedirects(false);

		return connection;
	}

	private static HttpURLConnection open(URI uri, String method, Map<String, String> headers, int connectTimeoutMillis)
		throws IOException {
		URL url = uri.toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		connection.setRequestMethod(method);
		connection.setConnectTimeout(Math.max(0, connectTimeoutMillis));
		connection.setUseCaches(false);

		if (headers != null) {
			for (Map.Entry<String, String> header : headers.entrySet()) {
				connection.setRequestProperty(header.getKey(), header.getValue());
			}
		}

		return connection;
	}

	private static byte[] readBody(HttpURLConnection connection, int status) throws IOException {
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();

		if (stream == null) {
			return new byte[0];
		}

		try {
			return drain(stream);
		} finally {
			stream.close();
		}
	}

	/** Read a stream to its end. `InputStream.readAllBytes` is Java 9. */
	public static byte[] drain(InputStream stream) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;

		while ((read = stream.read(chunk)) > 0) {
			buffer.write(chunk, 0, read);
		}

		return buffer.toByteArray();
	}
}
