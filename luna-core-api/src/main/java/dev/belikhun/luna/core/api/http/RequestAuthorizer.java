package dev.belikhun.luna.core.api.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Shared token check for Luna HTTP endpoints.
 *
 * The token is the Velocity forwarding secret, which backends already hold in
 * order to publish heartbeats, so the control console needs no additional
 * credential. It may be presented either as {@code X-Luna-Forwarding-Secret} or
 * as {@code Authorization: Bearer <token>} — the latter so browsers and generic
 * HTTP clients can reach the API without a custom header.
 *
 * A blank configured secret denies everything: an unset secret must never read as
 * "no authentication required".
 */
public final class RequestAuthorizer {
	public static final String TOKEN_HEADER = "X-Luna-Forwarding-Secret";
	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private final byte[] expected;

	public RequestAuthorizer(String secret) {
		this.expected = secret == null ? new byte[0] : secret.trim().getBytes(StandardCharsets.UTF_8);
	}

	/** Whether a usable secret is configured at all. */
	public boolean configured() {
		return expected.length > 0;
	}

	/** Whether a request carries the expected token. */
	public boolean authorized(Map<String, List<String>> headers) {
		if (!configured() || headers == null || headers.isEmpty()) {
			return false;
		}

		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			String key = entry.getKey();
			if (key == null || entry.getValue() == null) {
				continue;
			}

			boolean tokenHeader = TOKEN_HEADER.equalsIgnoreCase(key);
			boolean authorizationHeader = AUTHORIZATION_HEADER.equalsIgnoreCase(key);
			if (!tokenHeader && !authorizationHeader) {
				continue;
			}

			for (String value : entry.getValue()) {
				if (value == null) {
					continue;
				}

				String candidate = authorizationHeader ? stripBearer(value) : value.trim();
				if (matches(candidate)) {
					return true;
				}
			}
		}

		return false;
	}

	/** Convenience overload for a whole request. */
	public boolean authorized(HttpRequest request) {
		return request != null && authorized(request.headers());
	}

	/** The 401 response every endpoint returns on a failed check. */
	public HttpResponse unauthorized() {
		return LunaJson.error(401, configured() ? "unauthorized" : "http token is not configured");
	}

	private String stripBearer(String value) {
		String trimmed = value.trim();
		if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return trimmed.substring(BEARER_PREFIX.length()).trim();
		}
		return trimmed;
	}

	/** Constant-time comparison, so a wrong token leaks no length or prefix timing. */
	private boolean matches(String candidate) {
		return MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8));
	}
}
