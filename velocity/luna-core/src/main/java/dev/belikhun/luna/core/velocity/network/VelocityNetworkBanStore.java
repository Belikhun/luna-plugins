package dev.belikhun.luna.core.velocity.network;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.network.firewall.IpRanges;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Network-level IP bans, enforced by the proxy itself at pre-login: a banned
 * address is refused before it can write a directory profile, fire a messenger
 * relay event or reach any backend, which is what the per-backend
 * banned-ips.json files cannot do.
 *
 * A ban names one address or a prefix (an IPv6 /64, a hosting range). It
 * records where it came from ({@code manual}, or the firewall detector that
 * made it) and its strike: how many times the address has been banned, which
 * is what lengthens an automatic ban on each repeat. A ban that ends, by
 * expiring, being lifted or being replaced by a newer one, is moved to the
 * history table, so a repeat offender stays visible after the ban lifts.
 *
 * Bans persist in the shared MariaDB database but are enforced from memory,
 * because the check runs on every connection attempt and scanners hammer the
 * port; the database is only touched on mutation, on reload, and by a
 * rate-limited hit counter so the console can show that a banned address is
 * still knocking. Enforcement itself lives in the firewall's pre-login
 * handler, which asks {@link #activeBan} first.
 *
 * Created once for the plugin's lifetime and survives config reloads; only the
 * {@link Database} handle is swapped via {@link #attach}.
 */
public final class VelocityNetworkBanStore {
	/** How often one banned address's knocking is worth a database write. */
	private static final long HIT_PERSIST_INTERVAL_MS = 60_000L;

	private final LunaLogger logger;
	private final ExecutorService writer;
	private final ConcurrentHashMap<String, Entry> bans;
	/** Past bans per address, the current one excluded; what a new ban's strike counts from */
	private final ConcurrentHashMap<String, Integer> pastStrikes;
	/** The prefix bans, compiled for matching; rebuilt on every change */
	private volatile IpRanges prefixes;
	private volatile Database database;

	/** One ban, cached for enforcement; hit fields mutate in memory first. */
	public static final class Entry {
		public final String ip;
		public final String reason;
		public final String actor;
		public final String source;
		public final int strikes;
		public final long createdAt;
		/** 0 = permanent */
		public final long expiresAt;
		public volatile long hits;
		public volatile long lastHitAt;
		private volatile long lastPersistedHitAt;

		private Entry(String ip, String reason, String actor, String source, int strikes, long createdAt, long expiresAt, long hits, long lastHitAt) {
			this.ip = ip;
			this.reason = reason;
			this.actor = actor;
			this.source = source;
			this.strikes = strikes;
			this.createdAt = createdAt;
			this.expiresAt = expiresAt;
			this.hits = hits;
			this.lastHitAt = lastHitAt;
			this.lastPersistedHitAt = lastHitAt;
		}

		/** Whether it names a prefix rather than one address. */
		public boolean prefix() {
			return ip.contains("/");
		}

		public boolean automatic() {
			return !"manual".equals(source);
		}
	}

	/** A ban that has ended, as the history keeps it. */
	public record Ended(
		String ip,
		String reason,
		String actor,
		String source,
		int strikes,
		long createdAt,
		long expiresAt,
		long hits,
		long endedAt,
		String ended,
		String endedBy
	) {
	}

	public VelocityNetworkBanStore(LunaLogger logger) {
		this.logger = logger.scope("NetworkBans");
		this.database = new NoopDatabase();
		this.bans = new ConcurrentHashMap<>();
		this.pastStrikes = new ConcurrentHashMap<>();
		this.prefixes = IpRanges.empty();

		this.writer = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-network-ban-writer");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Swap the database handle on reload and rebuild the enforcement cache. */
	public void attach(Database nextDatabase) {
		this.database = nextDatabase;

		if (!available()) {
			return;
		}

		try {
			Map<String, Entry> loaded = new LinkedHashMap<>();

			for (Map<String, Object> row : database.query("SELECT * FROM luna_network_ip_bans", List.of())) {
				Entry entry = fromRow(row);
				loaded.put(entry.ip, entry);
			}

			Map<String, Integer> strikes = new LinkedHashMap<>();

			for (Map<String, Object> row : database.query("SELECT ip, COUNT(*) AS n FROM luna_network_ip_ban_history GROUP BY ip", List.of())) {
				strikes.put(String.valueOf(row.get("ip")), (int) longOf(row.get("n")));
			}

			bans.clear();
			bans.putAll(loaded);
			pastStrikes.clear();
			pastStrikes.putAll(strikes);
			rebuildPrefixes();
			logger.audit("Đã nạp " + bans.size() + " lệnh cấm IP toàn mạng.");
		} catch (RuntimeException exception) {
			logger.error("Không nạp được danh sách cấm IP toàn mạng: " + exception.getMessage(), exception);
		}
	}

	/** Whether a usable database is attached. */
	public boolean available() {
		return !(database instanceof NoopDatabase);
	}

	public void shutdown() {
		writer.shutdown();

		try {
			if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
				writer.shutdownNow();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			writer.shutdownNow();
		}
	}

	/** Every ban in force, newest first, as plain rows for the HTTP layer. */
	public List<Entry> list() {
		long now = System.currentTimeMillis();

		sweep(now);

		List<Entry> out = new ArrayList<>(bans.values());
		out.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
		return out;
	}

	/** Ended bans, newest first: one address's, or everyone's; at most {@code limit}. */
	public List<Ended> history(String ip, int limit) {
		if (!available()) {
			return List.of();
		}

		String key = ip == null ? "" : normalizeTarget(ip);
		List<Map<String, Object>> rows = key.isEmpty()
			? database.query("SELECT * FROM luna_network_ip_ban_history ORDER BY ended_at DESC LIMIT ?", List.of(limit))
			: database.query("SELECT * FROM luna_network_ip_ban_history WHERE ip = ? ORDER BY ended_at DESC LIMIT ?", List.of(key, limit));
		List<Ended> out = new ArrayList<>();

		for (Map<String, Object> row : rows) {
			out.add(new Ended(
				String.valueOf(row.get("ip")),
				row.get("reason") == null ? "" : String.valueOf(row.get("reason")),
				row.get("actor") == null ? "" : String.valueOf(row.get("actor")),
				row.get("source") == null ? "manual" : String.valueOf(row.get("source")),
				(int) longOf(row.get("strikes")),
				longOf(row.get("created_at")),
				longOf(row.get("expires_at")),
				longOf(row.get("hits")),
				longOf(row.get("ended_at")),
				String.valueOf(row.get("ended")),
				row.get("ended_by") == null ? "" : String.valueOf(row.get("ended_by"))
			));
		}

		return out;
	}

	/** The strike a new ban of this address would be: its past bans plus one. */
	public int nextStrike(String ip) {
		String key = normalizeTarget(ip);
		Entry current = bans.get(key);

		return pastStrikes.getOrDefault(key, 0) + (current == null ? 0 : 1) + 1;
	}

	/** Add or replace a manual ban; {@code expiresAt} of 0 means permanent. */
	public Entry add(String ip, String reason, String actor, long expiresAt) {
		return add(ip, reason, actor, expiresAt, "manual");
	}

	/**
	 * Add or replace a ban from {@code source} ({@code manual}, or the detector
	 * that made it). A ban it replaces moves to the history as {@code replaced};
	 * the knock history carries over, since it is the same address knocking.
	 */
	public Entry add(String ip, String reason, String actor, long expiresAt, String source) {
		String key = normalizeTarget(ip);
		long now = System.currentTimeMillis();
		Entry previous = bans.get(key);
		int strikes = nextStrike(key);

		if (previous != null) {
			archive(previous, now, "replaced", actor);
		}

		Entry entry = new Entry(
			key,
			reason == null ? "" : reason,
			actor == null ? "" : actor,
			source == null || source.isBlank() ? "manual" : source,
			strikes,
			now,
			expiresAt,
			previous == null ? 0L : previous.hits,
			previous == null ? 0L : previous.lastHitAt
		);

		bans.put(key, entry);
		rebuildPrefixes();
		submit("add-ban", () -> database.update(
			"REPLACE INTO luna_network_ip_bans (ip, reason, actor, source, strikes, created_at, expires_at, hits, last_hit_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			List.of(entry.ip, entry.reason, entry.actor, entry.source, entry.strikes, entry.createdAt, entry.expiresAt, entry.hits, entry.lastHitAt)
		));
		logger.audit("Cấm IP toàn mạng: " + key + " [" + entry.source + ", lần " + strikes + "]" + (entry.reason.isBlank() ? "" : " (" + entry.reason + ")"));
		return entry;
	}

	/** Lift a ban; answers whether one existed. */
	public boolean remove(String ip) {
		return remove(ip, "");
	}

	/** Lift a ban as {@code actor}; the ban moves to the history as {@code lifted}. */
	public boolean remove(String ip, String actor) {
		String key = normalizeTarget(ip);
		Entry removed = bans.remove(key);

		if (removed == null) {
			return false;
		}

		rebuildPrefixes();
		archive(removed, System.currentTimeMillis(), "lifted", actor);
		submit("remove-ban", () -> database.update(
			"DELETE FROM luna_network_ip_bans WHERE ip = ?",
			List.of(key)
		));
		logger.audit("Ân xá IP toàn mạng: " + key);
		return true;
	}

	/** The ban in force for an address, by the address itself or a prefix holding it; null when clear. */
	public Entry activeBan(String ip, long now) {
		String key = normalizeTarget(ip);
		Entry entry = bans.get(key);

		if (entry == null) {
			IpRanges.Range range = prefixes.match(IpRanges.parseAddress(ip));

			entry = range == null
				? null
				: bans.get(range.text());
		}

		if (entry == null) {
			return null;
		}

		if (entry.expiresAt > 0 && entry.expiresAt <= now) {
			expire(entry, now);
			return null;
		}

		return entry;
	}

	/** Count a refused attempt; persisted at most once a minute per ban. */
	public void recordHit(Entry entry, long now) {
		entry.hits += 1;
		entry.lastHitAt = now;

		if (now - entry.lastPersistedHitAt < HIT_PERSIST_INTERVAL_MS) {
			return;
		}

		entry.lastPersistedHitAt = now;
		submit("record-hit", () -> database.update(
			"UPDATE luna_network_ip_bans SET hits = ?, last_hit_at = ? WHERE ip = ?",
			List.of(entry.hits, entry.lastHitAt, entry.ip)
		));
	}

	/** A plausible ban target: a bare IPv4 or IPv6 address, or a prefix of one; never a hostname. */
	public static boolean validIp(String ip) {
		return IpRanges.parseRange(ip) != null;
	}

	/** Expire every ban past its time, so a list never shows a ban that no longer holds. */
	private void sweep(long now) {
		for (Entry entry : List.copyOf(bans.values())) {
			if (entry.expiresAt > 0 && entry.expiresAt <= now) {
				expire(entry, now);
			}
		}
	}

	private void expire(Entry entry, long now) {
		if (!bans.remove(entry.ip, entry)) {
			return;
		}

		rebuildPrefixes();
		archive(entry, entry.expiresAt > 0 ? entry.expiresAt : now, "expired", "");
		submit("expire-ban", () -> database.update(
			"DELETE FROM luna_network_ip_bans WHERE ip = ?",
			List.of(entry.ip)
		));
	}

	/** Keep an ended ban in the history and count it as a strike against the address. */
	private void archive(Entry entry, long endedAt, String how, String endedBy) {
		pastStrikes.merge(entry.ip, 1, Integer::sum);
		submit("archive-ban", () -> database.update(
			"INSERT INTO luna_network_ip_ban_history (ip, reason, actor, source, strikes, created_at, expires_at, hits, ended_at, ended, ended_by)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			List.of(entry.ip, entry.reason, entry.actor, entry.source, entry.strikes, entry.createdAt, entry.expiresAt, entry.hits, endedAt, how, endedBy == null ? "" : endedBy)
		));
	}

	private void rebuildPrefixes() {
		List<String> list = new ArrayList<>();

		for (Entry entry : bans.values()) {
			if (entry.prefix()) {
				list.add(entry.ip);
			}
		}

		prefixes = IpRanges.of(list);
	}

	/** The canonical text of an address or prefix; the input lowercased when it does not parse. */
	private String normalizeTarget(String ip) {
		IpRanges.Range range = IpRanges.parseRange(ip);

		if (range != null) {
			return range.text();
		}

		return ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
	}

	private Entry fromRow(Map<String, Object> row) {
		return new Entry(
			String.valueOf(row.get("ip")),
			row.get("reason") == null ? "" : String.valueOf(row.get("reason")),
			row.get("actor") == null ? "" : String.valueOf(row.get("actor")),
			row.get("source") == null ? "manual" : String.valueOf(row.get("source")),
			Math.max(1, (int) longOf(row.get("strikes"))),
			longOf(row.get("created_at")),
			longOf(row.get("expires_at")),
			longOf(row.get("hits")),
			longOf(row.get("last_hit_at"))
		);
	}

	private long longOf(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		if (value == null) {
			return 0L;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private void submit(String what, Runnable work) {
		if (!available()) {
			return;
		}

		try {
			writer.execute(() -> {
				try {
					work.run();
				} catch (RuntimeException exception) {
					logger.error("Ghi lệnh cấm IP toàn mạng thất bại (" + what + "): " + exception.getMessage(), exception);
				}
			});
		} catch (RejectedExecutionException shuttingDown) {
			// Writes after shutdown are dropped on purpose; the proxy is going away.
		}
	}
}
