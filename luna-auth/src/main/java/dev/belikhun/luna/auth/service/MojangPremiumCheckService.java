package dev.belikhun.luna.auth.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojangPremiumCheckService {
	private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");

	private final LunaLogger logger;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final long cacheMillis;
	private final Map<String, CachedProfile> cache;
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
		return findOnlineUuid(username).isPresent();
	}

	public Optional<UUID> findOnlineUuid(String username) {
		String normalized = normalize(username);
		if (normalized.isBlank()) {
			return Optional.empty();
		}

		long now = System.currentTimeMillis();
		CachedProfile cached = cache.get(normalized);
		if (cached != null && cached.expiresAtEpochMillis() >= now) {
			flow("Premium-check cache hit username=" + normalized + " premium=" + cached.onlineUuid().isPresent());
			return cached.onlineUuid();
		}

		flow("Premium-check cache miss username=" + normalized + ", gọi Mojang API.");
		Optional<UUID> onlineUuid = queryOnlineUuid(normalized);
		cache.put(normalized, new CachedProfile(onlineUuid, now + cacheMillis));
		flow("Premium-check cache store username=" + normalized + " premium=" + onlineUuid.isPresent() + " ttlMillis=" + cacheMillis);
		return onlineUuid;
	}

	private Optional<UUID> queryOnlineUuid(String username) {
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
				Optional<UUID> onlineUuid = parseOnlineUuid(response.body());
				logger.audit("Premium-check Mojang API username=" + username + " status=200 premium=" + onlineUuid.isPresent() + " latencyMs=" + (System.currentTimeMillis() - start));
				return onlineUuid;
			}
			if (status == 204 || status == 404) {
				logger.audit("Premium-check Mojang API username=" + username + " status=" + status + " premium=false latencyMs=" + (System.currentTimeMillis() - start));
				return Optional.empty();
			}

			logger.warn("Premium-check Mojang API trả về mã bất thường " + status + " cho username=" + username);
			return Optional.empty();
		} catch (Exception exception) {
			logger.warn("Không thể kiểm tra premium username=" + username + ". Bỏ qua premium force cho lượt này. Lý do="
				+ exception.getClass().getSimpleName() + ": " + exception.getMessage());
			return Optional.empty();
		}
	}

	private Optional<UUID> parseOnlineUuid(String body) {
		if (body == null || body.isBlank()) {
			return Optional.empty();
		}

		Matcher matcher = ID_PATTERN.matcher(body);
		if (!matcher.find()) {
			return Optional.empty();
		}

		String rawId = matcher.group(1);
		String formatted = rawId.substring(0, 8)
			+ "-" + rawId.substring(8, 12)
			+ "-" + rawId.substring(12, 16)
			+ "-" + rawId.substring(16, 20)
			+ "-" + rawId.substring(20);
		return Optional.of(UUID.fromString(formatted));
	}

	private String normalize(String username) {
		return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
	}

	private void flow(String message) {
		if (flowLogsEnabled) {
			logger.audit(message);
		}
	}

	private record CachedProfile(Optional<UUID> onlineUuid, long expiresAtEpochMillis) {
	}
}
