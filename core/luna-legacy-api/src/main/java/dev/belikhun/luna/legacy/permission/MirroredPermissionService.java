package dev.belikhun.luna.legacy.permission;

import dev.belikhun.luna.legacy.http.LegacyHttp;
import dev.belikhun.luna.legacy.http.ProxyEndpoints;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * LuckPerms, mirrored from the proxy.
 *
 * No build of LuckPerms exists for Forge below 1.19, so the 1.12.2 backend cannot ask
 * it anything locally. The proxy can: it runs LuckPerms against the same MariaDB
 * storage every other server shares, and it already exposes the permission editor's
 * API. This fetches each player's *resolved* set from there once, keeps it for the
 * session, and answers every check locally.
 *
 * **Nothing here ever blocks the server thread.** A check reads whatever is cached and
 * says UNDEFINED if there is nothing - it does not go and get it. Fetching happens on
 * this class's own single thread, kicked off when a player joins and again when an
 * entry ages out. The alternative, a synchronous fetch inside `hasPermission`, would
 * put an HTTP round trip inside a command handler and stall the tick loop on a proxy
 * that is merely slow.
 *
 * The consequence is worth stating rather than hiding: for the first few hundred
 * milliseconds of a session a player's permissions are UNDEFINED, and every caller
 * decides what that means for its own node. That is exactly why
 * {@link #hasPermissionOrDefault} exists and why a node whose absence means *allowed*
 * has to use it.
 */
public final class MirroredPermissionService implements PermissionService, AutoCloseable {
	/** How long a cached snapshot is served before a refresh is queued behind it. */
	private static final long DEFAULT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private static final String SECRET_HEADER = "X-Luna-Forwarding-Secret";

	private final LunaLogger logger;
	private final URI resolveBase;
	private final String secret;
	private final String serverName;
	private final int connectTimeoutMillis;
	private final int readTimeoutMillis;
	private final long ttlMillis;

	private final Map<UUID, PermissionSnapshot> cache = new ConcurrentHashMap<UUID, PermissionSnapshot>();

	/**
	 * Lowercased name to uuid, for the lookups that start from a name.
	 *
	 * An admin asking why someone cannot do something usually asks while that someone
	 * is offline, so there is no player object to take a uuid from. The proxy resolves
	 * a name itself, so this is just the index that lets a second lookup hit the cache
	 * the first one filled.
	 */
	private final Map<String, UUID> byName = new ConcurrentHashMap<String, UUID>();

	/** Fetches already queued, so a burst of checks on a cold entry makes one request. */
	private final Map<String, Boolean> inFlight = new ConcurrentHashMap<String, Boolean>();

	private final ExecutorService fetcher;

	/**
	 * @param heartbeatUri the endpoint from config.yml; the mirror lives beside it
	 * @param secret       the forwarding secret, the same one the heartbeat presents
	 * @param serverName   this backend's registered name, so LuckPerms resolves the
	 *                     snapshot in this server's context rather than the proxy's
	 */
	public MirroredPermissionService(
		LunaLogger logger,
		URI heartbeatUri,
		String secret,
		String serverName,
		int connectTimeoutMillis,
		int readTimeoutMillis
	) {
		this(logger, heartbeatUri, secret, serverName, connectTimeoutMillis, readTimeoutMillis, DEFAULT_TTL_MILLIS);
	}

	public MirroredPermissionService(
		LunaLogger logger,
		URI heartbeatUri,
		String secret,
		String serverName,
		int connectTimeoutMillis,
		int readTimeoutMillis,
		long ttlMillis
	) {
		this.logger = logger.scope("Permissions");
		this.resolveBase = ProxyEndpoints.sibling(heartbeatUri, "/permissions/resolve/");
		this.secret = secret == null ? "" : secret;
		this.serverName = Strings.trimmed(serverName);
		this.connectTimeoutMillis = Math.max(500, connectTimeoutMillis);
		this.readTimeoutMillis = Math.max(500, readTimeoutMillis);
		this.ttlMillis = Math.max(1000L, ttlMillis);

		this.fetcher = Executors.newSingleThreadExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "luna-permission-mirror");
				thread.setDaemon(true);
				return thread;
			}
		});
	}

	/** Whether the mirror is configured well enough to fetch anything at all. */
	@Override
	public boolean isAvailable() {
		return resolveBase != null && !Strings.isBlank(secret);
	}

	// ------------------------------------------------------------------ lookups

	/**
	 * What the snapshot says, with no opinion about what unset means.
	 *
	 * Also the cache's read trigger: a miss or a stale entry queues a fetch for next
	 * time, so a check is what keeps the mirror warm without ever waiting on it.
	 */
	public Tristate check(UUID uniqueId, String permission) {
		if (uniqueId == null) {
			return Tristate.UNDEFINED;
		}

		PermissionSnapshot snapshot = cache.get(uniqueId);

		if (snapshot == null) {
			refresh(uniqueId, null);
			return Tristate.UNDEFINED;
		}

		if (snapshot.olderThan(ttlMillis)) {
			refresh(uniqueId, snapshot.username());
		}

		return snapshot.check(permission);
	}

	/** An admin verb: nobody has it until someone was given it. */
	@Override
	public boolean hasPermission(UUID uniqueId, String permission) {
		return check(uniqueId, permission).orElse(false);
	}

	/**
	 * A permission whose absence is not a denial.
	 *
	 * Bukkit's `PermissionDefault.TRUE` semantics, and the reason the whole mirror
	 * returns a {@link Tristate}: a cold cache has to read as *unset*, so a node
	 * everybody is meant to have keeps working while the snapshot is still in flight.
	 */
	@Override
	public boolean hasPermissionOrDefault(UUID uniqueId, String permission, boolean fallback) {
		return check(uniqueId, permission).orElse(fallback);
	}

	/** The cached snapshot, if there is one; never fetches. */
	public PermissionSnapshot snapshot(UUID uniqueId) {
		return uniqueId == null ? null : cache.get(uniqueId);
	}

	@Override
	public String groupName(UUID uniqueId) {
		PermissionSnapshot snapshot = snapshot(uniqueId);

		return snapshot == null ? "" : snapshot.primaryGroup();
	}

	@Override
	public String prefix(UUID uniqueId) {
		PermissionSnapshot snapshot = snapshot(uniqueId);

		return snapshot == null ? "" : snapshot.prefix();
	}

	@Override
	public String suffix(UUID uniqueId) {
		PermissionSnapshot snapshot = snapshot(uniqueId);

		return snapshot == null ? "" : snapshot.suffix();
	}

	// ------------------------------------------------------------------ cache lifecycle

	/** The cached snapshot for a name, if some earlier fetch indexed it. */
	public PermissionSnapshot snapshotByName(String username) {
		if (Strings.isBlank(username)) {
			return null;
		}

		UUID uniqueId = byName.get(username.trim().toLowerCase(Locale.ROOT));

		return uniqueId == null ? null : cache.get(uniqueId);
	}

	/** Fetch a player's snapshot ahead of the first check; call this on join. */
	public void warm(UUID uniqueId, String username) {
		refresh(uniqueId, username);
	}

	/**
	 * Fetch by name, for a player who is not on this server.
	 *
	 * The proxy's route accepts a name where it accepts a uuid and resolves it against
	 * LuckPerms' own username index, so this needs nothing the backend does not have.
	 */
	public void warmByName(String username) {
		if (Strings.isBlank(username) || !isAvailable()) {
			return;
		}

		queue(username.trim().toLowerCase(Locale.ROOT), username.trim(), null);
	}

	/** Drop a player's snapshot; call this on quit, so a rejoin re-reads the proxy. */
	public void forget(UUID uniqueId) {
		if (uniqueId == null) {
			return;
		}

		PermissionSnapshot removed = cache.remove(uniqueId);

		if (removed != null && !Strings.isBlank(removed.username())) {
			byName.remove(removed.username().toLowerCase(Locale.ROOT));
		}
	}

	/** Drop every snapshot, so the next check of each player re-reads the proxy. */
	public void invalidateAll() {
		cache.clear();
		byName.clear();
	}

	public int cachedCount() {
		return cache.size();
	}

	@Override
	public void close() {
		fetcher.shutdownNow();
		cache.clear();
		inFlight.clear();
	}

	// ------------------------------------------------------------------ fetching

	private void refresh(UUID uniqueId, String username) {
		if (uniqueId == null || !isAvailable()) {
			return;
		}

		queue(uniqueId.toString(), uniqueId.toString(), username);
	}

	/**
	 * Queue one fetch for a player, keyed so concurrent callers coalesce.
	 *
	 * The proxy resolves a uuid and a name through the same route, so `reference` is
	 * whichever the caller had. `key` is what stops a burst of checks on a cold entry
	 * from firing a request each: it is the reference itself, meaning a lookup by name
	 * and a lookup by uuid for the same player can both be in flight once - which is
	 * the correct behaviour, since neither knows yet that they are the same player.
	 */
	private void queue(String key, final String reference, final String username) {
		if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
			return;
		}

		final String marker = key;

		try {
			fetcher.execute(new Runnable() {
				@Override
				public void run() {
					try {
						fetchNow(reference, username);
					} finally {
						inFlight.remove(marker);
					}
				}
			});
		} catch (RuntimeException rejected) {
			// the executor is shutting down; drop the marker so a later call can retry
			inFlight.remove(marker);
		}
	}

	private void fetchNow(String reference, String username) {
		URI uri = resolveUri(reference);

		if (uri == null) {
			return;
		}

		String label = Strings.isBlank(username) ? reference : username;

		try {
			LegacyHttp.Response response = LegacyHttp.get(uri, headers(), connectTimeoutMillis, readTimeoutMillis);

			if (!response.ok()) {
				logger.debug("Proxy trả về " + response.status() + " khi lấy quyền của " + label + ".");
				return;
			}

			PermissionSnapshot snapshot = PermissionSnapshotCodec.decode(response.body());

			if (snapshot == null) {
				logger.warn("Không đọc được snapshot quyền của " + label + ".");
				return;
			}

			cache.put(snapshot.uniqueId(), snapshot);

			if (!Strings.isBlank(snapshot.username())) {
				byName.put(snapshot.username().toLowerCase(Locale.ROOT), snapshot.uniqueId());
			}

			logger.debug("Đã đồng bộ " + snapshot.permissions().size() + " quyền cho "
				+ snapshot.username() + " (nhóm chính: " + snapshot.primaryGroup() + ").");
		} catch (IOException failure) {
			// a mirror that cannot reach the proxy is a degraded server, not a broken
			// one, so this is a warning and the last good snapshot stays cached
			logger.warn("Không lấy được quyền của " + label + " từ proxy: " + failure.getMessage());
		}
	}

	private URI resolveUri(String reference) {
		if (resolveBase == null) {
			return null;
		}

		String uri = resolveBase.toString() + encode(reference);

		if (!Strings.isBlank(serverName)) {
			uri = uri + "?server=" + encode(serverName);
		}

		return URI.create(uri);
	}

	private Map<String, String> headers() {
		Map<String, String> headers = new LinkedHashMap<String, String>();

		headers.put(SECRET_HEADER, secret);
		headers.put("Accept", "application/x-www-form-urlencoded");

		return headers;
	}

	private static String encode(String value) {
		try {
			return URLEncoder.encode(value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException impossible) {
			return value;
		}
	}

}
