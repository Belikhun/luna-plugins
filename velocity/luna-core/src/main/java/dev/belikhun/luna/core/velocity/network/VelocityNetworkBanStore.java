package dev.belikhun.luna.core.velocity.network;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.net.InetSocketAddress;
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
 * Bans persist in the shared MariaDB database but are enforced from an
 * in-memory map, because the check runs on every connection attempt and
 * scanners hammer the port; the database is only touched on mutation, on
 * reload, and by a rate-limited hit counter so the console can show that a
 * banned address is still knocking.
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
	private volatile Database database;

	/** One ban, cached for enforcement; hit fields mutate in memory first. */
	public static final class Entry {
		public final String ip;
		public final String reason;
		public final String actor;
		public final long createdAt;
		/** 0 = permanent */
		public final long expiresAt;
		public volatile long hits;
		public volatile long lastHitAt;
		private volatile long lastPersistedHitAt;

		private Entry(String ip, String reason, String actor, long createdAt, long expiresAt, long hits, long lastHitAt) {
			this.ip = ip;
			this.reason = reason;
			this.actor = actor;
			this.createdAt = createdAt;
			this.expiresAt = expiresAt;
			this.hits = hits;
			this.lastHitAt = lastHitAt;
			this.lastPersistedHitAt = lastHitAt;
		}
	}

	public VelocityNetworkBanStore(LunaLogger logger) {
		this.logger = logger.scope("NetworkBans");
		this.database = new NoopDatabase();
		this.bans = new ConcurrentHashMap<>();

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

			bans.clear();
			bans.putAll(loaded);
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

	/** Every ban, newest first, as plain rows for the HTTP layer. */
	public List<Entry> list() {
		List<Entry> out = new ArrayList<>(bans.values());
		out.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
		return out;
	}

	/** Add or replace a ban; {@code expiresAt} of 0 means permanent. */
	public Entry add(String ip, String reason, String actor, long expiresAt) {
		String key = normalizeIp(ip);
		long now = System.currentTimeMillis();
		Entry previous = bans.get(key);

		// re-banning keeps the knock history; only the terms are replaced
		Entry entry = new Entry(
			key,
			reason == null ? "" : reason,
			actor == null ? "" : actor,
			now,
			expiresAt,
			previous == null ? 0L : previous.hits,
			previous == null ? 0L : previous.lastHitAt
		);

		bans.put(key, entry);
		submit("add-ban", () -> database.update(
			"REPLACE INTO luna_network_ip_bans (ip, reason, actor, created_at, expires_at, hits, last_hit_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?)",
			List.of(entry.ip, entry.reason, entry.actor, entry.createdAt, entry.expiresAt, entry.hits, entry.lastHitAt)
		));

		logger.audit("Cấm IP toàn mạng: " + key + (entry.reason.isBlank() ? "" : " (" + entry.reason + ")"));
		return entry;
	}

	/** Lift a ban; answers whether one existed. */
	public boolean remove(String ip) {
		String key = normalizeIp(ip);
		Entry removed = bans.remove(key);

		submit("remove-ban", () -> database.update(
			"DELETE FROM luna_network_ip_bans WHERE ip = ?",
			List.of(key)
		));

		if (removed != null) {
			logger.audit("Ân xá IP toàn mạng: " + key);
		}

		return removed != null;
	}

	/** The active ban for an address, expiry already applied; null when clear. */
	public Entry activeBan(String ip, long now) {
		Entry entry = bans.get(normalizeIp(ip));

		if (entry == null) {
			return null;
		}

		if (entry.expiresAt > 0 && entry.expiresAt <= now) {
			// expired: drop it lazily so the list stays honest without a sweeper
			remove(entry.ip);
			return null;
		}

		return entry;
	}

	/**
	 * The enforcement point. FIRST order so a banned address is refused before
	 * luna-auth, the record store or anything else spends work on it.
	 */
	@Subscribe(order = PostOrder.FIRST)
	public void onPreLogin(PreLoginEvent event) {
		InetSocketAddress remote = event.getConnection().getRemoteAddress();

		if (remote == null || remote.getAddress() == null) {
			return;
		}

		long now = System.currentTimeMillis();
		Entry entry = activeBan(remote.getAddress().getHostAddress(), now);

		if (entry == null) {
			return;
		}

		recordHit(entry, now);

		Component message = Component.text("Địa chỉ IP của bạn đã bị cấm khỏi máy chủ.", NamedTextColor.RED);
		if (!entry.reason.isBlank()) {
			message = message.append(Component.newline())
				.append(Component.text("Lý do: " + entry.reason, NamedTextColor.GRAY));
		}

		event.setResult(PreLoginEvent.PreLoginComponentResult.denied(message));
	}

	/** Count a refused attempt; persisted at most once a minute per address. */
	private void recordHit(Entry entry, long now) {
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

	/** A plausible address: bare IPv4 or IPv6 text, never a hostname. */
	public static boolean validIp(String ip) {
		String trimmed = ip == null ? "" : ip.trim();

		if (trimmed.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
			return true;
		}

		return trimmed.contains(":") && trimmed.matches("^[0-9a-fA-F:.]{2,45}$");
	}

	private String normalizeIp(String ip) {
		return ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
	}

	private Entry fromRow(Map<String, Object> row) {
		return new Entry(
			String.valueOf(row.get("ip")),
			row.get("reason") == null ? "" : String.valueOf(row.get("reason")),
			row.get("actor") == null ? "" : String.valueOf(row.get("actor")),
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
