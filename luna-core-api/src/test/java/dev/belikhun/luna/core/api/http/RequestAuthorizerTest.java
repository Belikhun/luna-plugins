package dev.belikhun.luna.core.api.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RequestAuthorizerTest {
	private static final String SECRET = "s3cr3t-forwarding";

	@Test
	void acceptsTheDedicatedHeaderCaseInsensitively() {
		RequestAuthorizer authorizer = new RequestAuthorizer(SECRET);

		assertTrue(authorizer.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of(SECRET))));
		assertTrue(authorizer.authorized(Map.of("x-luna-forwarding-secret", List.of(SECRET))));
	}

	@Test
	void acceptsBearerAuthorization() {
		RequestAuthorizer authorizer = new RequestAuthorizer(SECRET);

		assertTrue(authorizer.authorized(Map.of("Authorization", List.of("Bearer " + SECRET))));
		assertTrue(authorizer.authorized(Map.of("Authorization", List.of("bearer " + SECRET))));
		assertTrue(authorizer.authorized(Map.of("Authorization", List.of(SECRET))));
	}

	@Test
	void rejectsWrongMissingAndPartialTokens() {
		RequestAuthorizer authorizer = new RequestAuthorizer(SECRET);

		assertFalse(authorizer.authorized(Map.of()));
		assertFalse(authorizer.authorized(Map.of("X-Other", List.of(SECRET))));
		assertFalse(authorizer.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of("wrong"))));
		assertFalse(authorizer.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of(SECRET.substring(0, 4)))));
		assertFalse(authorizer.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of(SECRET + "x"))));
	}

	@Test
	void findsTheTokenAmongSeveralHeaderValues() {
		RequestAuthorizer authorizer = new RequestAuthorizer(SECRET);

		assertTrue(authorizer.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of("stale", SECRET))));
	}

	@Test
	void anUnconfiguredSecretDeniesEverything() {
		RequestAuthorizer blank = new RequestAuthorizer("   ");

		assertFalse(blank.configured());
		assertFalse(blank.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of(""))));
		assertFalse(blank.authorized(Map.of(RequestAuthorizer.TOKEN_HEADER, List.of("anything"))));
		assertEquals(401, blank.unauthorized().status());
	}

	@Test
	void toleratesNullHeaderValues() {
		RequestAuthorizer authorizer = new RequestAuthorizer(SECRET);

		assertFalse(authorizer.authorized((Map<String, List<String>>) null));
		assertFalse(authorizer.authorized((HttpRequest) null));
	}
}
