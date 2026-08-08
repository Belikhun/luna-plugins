package dev.belikhun.luna.core.api.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpResponse {
	private final int status;
	private final byte[] body;
	private final Map<String, String> headers;
	private final ResponseStreamer streamer;

	private HttpResponse(int status, byte[] body, Map<String, String> headers, ResponseStreamer streamer) {
		this.status = status;
		this.body = body;
		this.headers = headers;
		this.streamer = streamer;
	}

	public static HttpResponse text(int status, String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "text/plain; charset=utf-8");
		return new HttpResponse(status, body.getBytes(StandardCharsets.UTF_8), headers, null);
	}

	public static HttpResponse json(int status, String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json; charset=utf-8");
		return new HttpResponse(status, body.getBytes(StandardCharsets.UTF_8), headers, null);
	}

	public static HttpResponse bytes(int status, byte[] body, String contentType) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
		return new HttpResponse(status, body == null ? new byte[0] : body, headers, null);
	}

	/**
	 * An open-ended server-sent-events response. The server manager sends the
	 * headers, hands the response body to {@code streamer}, and deliberately leaves
	 * the exchange open — closing it belongs to the stream, once the subscriber has
	 * gone away.
	 */
	public static HttpResponse sse(ResponseStreamer streamer) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "text/event-stream; charset=utf-8");
		headers.put("Cache-Control", "no-cache, no-transform");
		headers.put("Connection", "keep-alive");
		// stops any reverse proxy in front of us from buffering the stream
		headers.put("X-Accel-Buffering", "no");
		return new HttpResponse(200, new byte[0], headers, streamer);
	}

	public HttpResponse withHeader(String key, String value) {
		Map<String, String> newHeaders = new LinkedHashMap<>(headers);
		newHeaders.put(key, value);
		return new HttpResponse(status, body, newHeaders, streamer);
	}

	public int status() {
		return status;
	}

	public byte[] body() {
		return body;
	}

	public Map<String, String> headers() {
		return headers;
	}

	/** Non-null when this response streams instead of carrying a fixed body. */
	public ResponseStreamer streamer() {
		return streamer;
	}
}
