package dev.belikhun.luna.auth.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MojangPremiumCheckService {
	private final LunaLogger logger;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final long cacheMillis;
	private final Map<String, CachedResult> cache;
	private final boolean flowLogsEnabled;

	public MojangPremiumCheckService(LunaLogger logger, long timeoutMillis, long cacheMinutes, boolean flowLogsEnabled) {
		this.logger = logger.scope("PremiumCheck");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(Math.max(500L, timeoutMillis)))
			.build();
		this.timeout = Duration.ofMillis(Math.max(500L, timeoutMillis));
		this.cacheMillis = Math.max(1L, cacheMinutes) * 60_000L;
		this.cache = new ConcurrentHashMap<>();
		this.flowLogsEnabled = flowLogsEnabled;
	}

	public boolean isPremiumUsername(String username) {
		String normalized = normalize(username);
		long now = System.currentTimeMillis();
		CachedResult cached = cache.get(normalized);
		if (cached != null && cached.expiresAtEpochMillis() >= now) {
			flow("Premium-check cache hit username=" + normalized + " premium=" + cached.premium());
			return cached.premium();
		}

		flow("Premium-check cache miss username=" + normalized + ", gọi Mojang API.");
		boolean premium = queryPremium(normalized);
		cache.put(normalized, new CachedResult(premium, now + cacheMillis));
		flow("Premium-check cache store username=" + normalized + " premium=" + premium + " ttlMillis=" + cacheMillis);
		return premium;
	}

	private boolean queryPremium(String username) {
		long start = System.currentTimeMillis();
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
				.timeout(timeout)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status == 200) {
				String body = response.body();
				boolean premium = body != null && body.contains("\"id\"") && body.contains("\"name\"");
				logger.audit("Premium-check Mojang API username=" + username + " status=200 premium=" + premium + " latencyMs=" + (System.currentTimeMillis() - start));
				return premium;
			}
			if (status == 204 || status == 404) {
				logger.audit("Premium-check Mojang API username=" + username + " status=" + status + " premium=false latencyMs=" + (System.currentTimeMillis() - start));
				return false;
			}

			logger.warn("Premium-check Mojang API trả về mã bất thường " + status + " cho username=" + username);
			return false;
		} catch (Exception exception) {
			logger.warn("Không thể kiểm tra premium username=" + username + ". Bỏ qua premium force cho lượt này. Lý do="
				+ exception.getClass().getSimpleName() + ": " + exception.getMessage());
			return false;
		}
	}

	private String normalize(String username) {
		return username == null ? "" : username.trim().toLowerCase();
	}

	private void flow(String message) {
		if (flowLogsEnabled) {
			logger.audit(message);
		}
	}

	private record CachedResult(boolean premium, long expiresAtEpochMillis) {
	}
}
