package dev.belikhun.luna.core.api.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record HttpRequest(
	String method,
	URI uri,
	Map<String, List<String>> headers,
	byte[] body,
	Map<String, String> query,
	Map<String, String> pathParams
) {
	public String bodyAsString() {
		return new String(body, StandardCharsets.UTF_8);
	}

	public String queryParam(String key, String fallback) {
		return query.getOrDefault(key, fallback);
	}

	public String pathParam(String key, String fallback) {
		return pathParams.getOrDefault(key, fallback);
	}

	public HttpRequest withPathParams(Map<String, String> params) {
		return new HttpRequest(method, uri, headers, body, query, Collections.unmodifiableMap(new HashMap<>(params)));
	}
}

