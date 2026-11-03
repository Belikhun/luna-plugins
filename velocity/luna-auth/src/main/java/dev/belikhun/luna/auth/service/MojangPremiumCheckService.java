package dev.belikhun.luna.auth.service;

import dev.belikhun.luna.core.api.cache.ExpiringCache;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a username belongs to a paid Minecraft account.
 *
 * A lookup has three outcomes, not two. Mojang answering "this profile exists"
 * or "no such profile" is a fact and is cached for the configured time. Mojang
 * not answering at all (a timeout, a 429, a 5xx) is not a fact about the name,
 * so it is never cached as "not premium": doing that once locked a premium
 * player into the offline password flow for a full hour, and made the admin
 * command refuse to switch them to online mode, after a single slow request.
 *
 * When every endpoint fails, the answer falls back to what the network already
 * knows: a name that has completed an online-mode login before (its offline
 * UUID is claimed by a Mojang UUID in the auth database) is still treated as
 * premium. Treating a known premium name as premium is the safe direction:
 * it forces Mojang authentication, so nobody can take the name offline.
 */
public final class MojangPremiumCheckService {
	private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");

	/** Endpoints asked in order; the second is Mojang's newer service and survives outages of the first. */
	private static final List<String> LOOKUP_ENDPOINTS = List.of(
		"https://api.mojang.com/users/profiles/minecraft/",
		"https://api.minecraftservices.com/minecraft/profile/lookup/name/"
	);

	/**
	 * How long a failed lookup is remembered, so a Mojang outage costs one slow
	 * request per name per minute instead of one per login attempt.
	 */
	private static final long FAILURE_BACKOFF_MILLIS = 60_000L;

	public enum Status {
		PREMIUM,
		NOT_PREMIUM,
		UNKNOWN
	}

	/**
	 * The outcome of a lookup.
	 *
	 * @param status    what is known about the name
	 * @param onlineUuid the Mojang UUID, when the name is premium
	 * @param source    where the answer came from: mojang, cache, known (auth database) or failed
	 * @param checkedAtEpochMillis when the answer was obtained
	 * @param error     why the lookup failed, when status is UNKNOWN or the answer is a fallback
	 */
	public record Lookup(Status status, UUID onlineUuid, String source, long checkedAtEpochMillis, String error) {
		public boolean premium() {
			return status == Status.PREMIUM;
		}
	}

	private final LunaLogger logger;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final ExpiringCache<String, Lookup> definitive;
	private final ExpiringCache<String, Lookup> failures;
	private final boolean flowLogsEnabled;
	private volatile Function<String, Optional<UUID>> knownPremiumResolver;

	public MojangPremiumCheckService(LunaLogger logger, long timeoutMillis, long cacheMinutes, boolean flowLogsEnabled) {
		this.logger = logger.scope("PremiumCheck");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(Math.max(500L, timeoutMillis)))
			.build();
		this.timeout = Duration.ofMillis(Math.max(500L, timeoutMillis));
		this.definitive = new ExpiringCache<>(Math.max(1L, cacheMinutes) * 60_000L);
		this.failures = new ExpiringCache<>(FAILURE_BACKOFF_MILLIS);
		this.flowLogsEnabled = flowLogsEnabled;
		this.knownPremiumResolver = username -> Optional.empty();
	}

	/**
	 * Name to Mojang UUID for names that completed an online-mode login before,
	 * consulted only when Mojang cannot be reached.
	 */
	public void setKnownPremiumResolver(Function<String, Optional<UUID>> resolver) {
		this.knownPremiumResolver = resolver == null
			? username -> Optional.empty()
			: resolver;
	}

	public boolean isPremiumUsername(String username) {
		return lookup(username).premium();
	}

	public Optional<UUID> findOnlineUuid(String username) {
		Lookup result = lookup(username);

		return result.premium()
			? Optional.ofNullable(result.onlineUuid())
			: Optional.empty();
	}

	/** Look a name up, answering from the cache when it holds a definitive answer. */
	public Lookup lookup(String username) {
		String normalized = normalize(username);

		if (normalized.isBlank()) {
			return new Lookup(Status.NOT_PREMIUM, null, "invalid", System.currentTimeMillis(), "");
		}

		Lookup cached = definitive.get(normalized).orElse(null);

		if (cached != null) {
			flow("Premium-check cache hit username=" + normalized + " premium=" + cached.premium());
			return new Lookup(cached.status(), cached.onlineUuid(), "cache", cached.checkedAtEpochMillis(), "");
		}

		Lookup recentFailure = failures.get(normalized).orElse(null);

		if (recentFailure != null) {
			flow("Premium-check bỏ qua Mojang vì vừa lỗi username=" + normalized + ", dùng dữ liệu đã biết.");
			return fallback(normalized, recentFailure.error());
		}

		return query(normalized);
	}

	/** Forget everything cached about a name and ask Mojang again now. */
	public Lookup refresh(String username) {
		String normalized = normalize(username);

		invalidate(normalized);

		if (normalized.isBlank()) {
			return new Lookup(Status.NOT_PREMIUM, null, "invalid", System.currentTimeMillis(), "");
		}

		return query(normalized);
	}

	/** What is known about a name without touching the network: the cache, else the auth database. */
	public Lookup peek(String username) {
		String normalized = normalize(username);

		if (normalized.isBlank()) {
			return new Lookup(Status.NOT_PREMIUM, null, "invalid", System.currentTimeMillis(), "");
		}

		Lookup cached = definitive.get(normalized).orElse(null);

		if (cached != null) {
			return new Lookup(cached.status(), cached.onlineUuid(), "cache", cached.checkedAtEpochMillis(), "");
		}

		Optional<UUID> known = known(normalized);

		if (known.isPresent()) {
			return new Lookup(Status.PREMIUM, known.get(), "known", 0L, "");
		}

		return new Lookup(Status.UNKNOWN, null, "unchecked", 0L, "");
	}

	public void invalidate(String username) {
		String normalized = normalize(username);

		definitive.remove(normalized);
		failures.remove(normalized);
	}

	/** Record a name Mojang has just authenticated, which settles the question without a lookup. */
	public void recordVerified(String username, UUID onlineUuid) {
		String normalized = normalize(username);

		if (normalized.isBlank() || onlineUuid == null) {
			return;
		}

		failures.remove(normalized);
		definitive.put(normalized, new Lookup(Status.PREMIUM, onlineUuid, "verified", System.currentTimeMillis(), ""));
	}

	private Lookup query(String normalized) {
		flow("Premium-check cache miss username=" + normalized + ", gọi Mojang API.");
		String lastError = "";

		for (String endpoint : LOOKUP_ENDPOINTS) {
			Attempt attempt = ask(endpoint, normalized);

			if (attempt.status() != Status.UNKNOWN) {
				Lookup result = new Lookup(attempt.status(), attempt.onlineUuid(), "mojang", System.currentTimeMillis(), "");

				definitive.put(normalized, result);
				failures.remove(normalized);
				flow("Premium-check cache store username=" + normalized + " premium=" + result.premium());
				return result;
			}

			lastError = attempt.error();
		}

		failures.put(normalized, new Lookup(Status.UNKNOWN, null, "failed", System.currentTimeMillis(), lastError));
		return fallback(normalized, lastError);
	}

	/** Mojang is unreachable: answer from the auth database, never from a guess. */
	private Lookup fallback(String normalized, String error) {
		Optional<UUID> known = known(normalized);

		if (known.isPresent()) {
			logger.warn("Không kiểm tra được premium username=" + normalized + ", dùng dữ liệu đã đăng nhập online trước đó (premium=true).");
			return new Lookup(Status.PREMIUM, known.get(), "known", System.currentTimeMillis(), error);
		}

		return new Lookup(Status.UNKNOWN, null, "failed", System.currentTimeMillis(), error);
	}

	private Optional<UUID> known(String normalized) {
		try {
			return knownPremiumResolver.apply(normalized);
		} catch (RuntimeException failure) {
			logger.warn("Không thể đọc dữ liệu premium đã biết username=" + normalized + ": " + failure.getMessage());
			return Optional.empty();
		}
	}

	private Attempt ask(String endpoint, String username) {
		long start = System.currentTimeMillis();

		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(endpoint + username))
				.timeout(timeout)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			long latency = System.currentTimeMillis() - start;

			if (status == 200) {
				Optional<UUID> onlineUuid = parseOnlineUuid(response.body());

				if (onlineUuid.isEmpty()) {
					logger.warn("Premium-check " + host(endpoint) + " trả về 200 nhưng không đọc được id cho username=" + username);
					return new Attempt(Status.UNKNOWN, null, "unreadable 200 response");
				}

				logger.audit("Premium-check " + host(endpoint) + " username=" + username + " status=200 premium=true latencyMs=" + latency);
				return new Attempt(Status.PREMIUM, onlineUuid.get(), "");
			}

			if (status == 204 || status == 404) {
				logger.audit("Premium-check " + host(endpoint) + " username=" + username + " status=" + status + " premium=false latencyMs=" + latency);
				return new Attempt(Status.NOT_PREMIUM, null, "");
			}

			logger.warn("Premium-check " + host(endpoint) + " trả về mã bất thường " + status + " cho username=" + username);
			return new Attempt(Status.UNKNOWN, null, "HTTP " + status);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return new Attempt(Status.UNKNOWN, null, "interrupted");
		} catch (Exception exception) {
			String reason = exception.getClass().getSimpleName() + ": " + exception.getMessage();

			logger.warn("Không thể kiểm tra premium qua " + host(endpoint) + " username=" + username + ". Lý do=" + reason);
			return new Attempt(Status.UNKNOWN, null, reason);
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

	private String host(String endpoint) {
		return URI.create(endpoint).getHost();
	}

	private String normalize(String username) {
		return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
	}

	private void flow(String message) {
		if (flowLogsEnabled) {
			logger.audit(message);
		}
	}

	private record Attempt(Status status, UUID onlineUuid, String error) {
	}
}
