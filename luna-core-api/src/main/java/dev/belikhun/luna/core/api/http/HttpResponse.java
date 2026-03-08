package dev.belikhun.luna.core.api.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpResponse {
	private final int status;
	private final byte[] body;
	private final Map<String, String> headers;

	private HttpResponse(int status, byte[] body, Map<String, String> headers) {
		this.status = status;
		this.body = body;
		this.headers = headers;
	}

	public static HttpResponse text(int status, String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "text/plain; charset=utf-8");
		return new HttpResponse(status, body.getBytes(StandardCharsets.UTF_8), headers);
	}

	public static HttpResponse json(int status, String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json; charset=utf-8");
		return new HttpResponse(status, body.getBytes(StandardCharsets.UTF_8), headers);
	}

	public HttpResponse withHeader(String key, String value) {
		Map<String, String> newHeaders = new LinkedHashMap<>(headers);
		newHeaders.put(key, value);
		return new HttpResponse(status, body, newHeaders);
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
}

