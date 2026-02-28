package dev.belikhun.luna.core.api.http;

import java.util.Map;

record Route(String method, String path, RouteHandler handler) {
	boolean matchesMethod(String requestMethod) {
		return method.equalsIgnoreCase(requestMethod);
	}

	MatchResult matchesPath(String requestPath) {
		String[] routeSegments = sanitize(path).split("/");
		String[] requestSegments = sanitize(requestPath).split("/");
		if (routeSegments.length != requestSegments.length) {
			return MatchResult.none();
		}

		Map<String, String> params = new java.util.LinkedHashMap<>();
		for (int index = 0; index < routeSegments.length; index++) {
			String routeSegment = routeSegments[index];
			String requestSegment = requestSegments[index];
			if (routeSegment.startsWith("{") && routeSegment.endsWith("}")) {
				String key = routeSegment.substring(1, routeSegment.length() - 1);
				params.put(key, requestSegment);
				continue;
			}

			if (!routeSegment.equals(requestSegment)) {
				return MatchResult.none();
			}
		}

		return MatchResult.of(params);
	}

	private String sanitize(String value) {
		if (value == null || value.isBlank() || value.equals("/")) {
			return "";
		}
		String cleaned = value.trim();
		if (cleaned.startsWith("/")) {
			cleaned = cleaned.substring(1);
		}
		if (cleaned.endsWith("/")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}
		return cleaned;
	}

	record MatchResult(boolean matched, Map<String, String> params) {
		static MatchResult none() {
			return new MatchResult(false, Map.of());
		}

		static MatchResult of(Map<String, String> params) {
			return new MatchResult(true, params);
		}
	}
}
