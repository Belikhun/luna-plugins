package dev.belikhun.luna.core.velocity.network;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;

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
 * Network-level player bans, the account-shaped sibling of
 * {@link VelocityNetworkBanStore}: a banned name is refused at pre-login,
 * before luna-auth or the record store spend any work on it, and a banned UUID
 * is refused again at login, which catches a premium account that came back
 * under a new name.
 *
 * Keyed by lower-cased username because that is all pre-login knows, and
 * because offline-mode UUIDs are derived from the name anyway. A ban may name a
 * player the network has never seen (a scanner's fixed bot name), in which case
 * the UUID stays blank.
 *
 * Same lifetime and persistence model as the IP store: enforced from memory,
 * written through one background writer, hits counted at most once a minute.
 */
public final class VelocityNetworkPlayerBanStore {
	private static final long HIT_PERSIST_INTERVAL_MS = 60_000L;

	private final LunaLogger logger;
	private final ExecutorService writer;
	private final ConcurrentHashMap<String, Entry> bans;
	private volatile Database database;

	/** One ban, cached for enforcement; hit fields mutate in memory first. */
	public static final class Entry {
		public final String usernameLower;
		public final String username;
		public final String uuid;
		public final String reason;
		public final String actor;
		public final long createdAt;
		/** 0 = permanent */
		public final long expiresAt;
		public volatile long hits;
		public volatile long lastHitAt;
		private volatile long lastPersistedHitAt;

		private Entry(
			String username,
			String uuid,
			String reason,
			String actor,
			long createdAt,
			long expiresAt,
			long hits,
			long lastHitAt
		) {
			this.username = username;
			this.usernameLower = username.toLowerCase(Locale.ROOT);
			this.uuid = uuid;
			this.reason = reason;
			this.actor = actor;
			this.createdAt = createdAt;
			this.expiresAt = expiresAt;
			this.hits = hits;
			this.lastHitAt = lastHitAt;
			this.lastPersistedHitAt = lastHitAt;
		}
	}

	public VelocityNetworkPlayerBanStore(LunaLogger logger) {
		this.logger = logger.scope("NetworkPlayerBans");
		this.database = new NoopDatabase();
		this.bans = new ConcurrentHashMap<>();

		this.writer = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-network-player-ban-writer");
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

			for (Map<String, Object> row : database.query("SELECT * FROM luna_network_bans", List.of())) {
				Entry entry = fromRow(row);
				loaded.put(entry.usernameLower, entry);
			}

			bans.clear();
			bans.putAll(loaded);
			logger.audit("Đã nạp " + bans.size() + " lệnh cấm người chơi toàn mạng.");
		} catch (RuntimeException exception) {
			logger.error("Không nạp được danh sách cấm người chơi toàn mạng: " + exception.getMessage(), exception);
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

	/** Every ban, newest first. */
	public List<Entry> list() {
		List<Entry> out = new ArrayList<>(bans.values());
		out.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
		return out;
	}

	/** Add or replace a ban; {@code expiresAt} of 0 means permanent. */
	public Entry add(String username, String uuid, String reason, String actor, long expiresAt) {
		String name = username == null ? "" : username.trim();
		String key = name.toLowerCase(Locale.ROOT);
		long now = System.currentTimeMillis();
		Entry previous = bans.get(key);

		// a re-ban that names no UUID keeps the one already known
		String knownUuid = uuid == null || uuid.isBlank()
			? (previous == null ? "" : previous.uuid)
			: uuid.trim().toLowerCase(Locale.ROOT);

		Entry entry = new Entry(
			name,
			knownUuid,
			reason == null ? "" : reason,
			actor == null ? "" : actor,
			now,
			expiresAt,
			previous == null ? 0L : previous.hits,
			previous == null ? 0L : previous.lastHitAt
		);

		bans.put(key, entry);
		submit("add-ban", () -> database.update(
			"REPLACE INTO luna_network_bans (username_lower, username, uuid, reason, actor, created_at, expires_at, hits, last_hit_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			List.of(
				entry.usernameLower,
				entry.username,
				entry.uuid,
				entry.reason,
				entry.actor,
				entry.createdAt,
				entry.expiresAt,
				entry.hits,
				entry.lastHitAt
			)
		));

		logger.audit("Cấm người chơi toàn mạng: " + entry.username
			+ (entry.reason.isBlank() ? "" : " (" + entry.reason + ")"));
		return entry;
	}

	/** Lift a ban by name or UUID; answers the entry that was removed, or null. */
	public Entry remove(String reference) {
		Entry entry = find(reference);

		if (entry == null) {
			return null;
		}

		bans.remove(entry.usernameLower);
		submit("remove-ban", () -> database.update(
			"DELETE FROM luna_network_bans WHERE username_lower = ?",
			List.of(entry.usernameLower)
		));

		logger.audit("Ân xá người chơi toàn mạng: " + entry.username);
		return entry;
	}

	/** The ban matching a username or UUID, expired or not; null when none. */
	public Entry find(String reference) {
		String key = reference == null ? "" : reference.trim().toLowerCase(Locale.ROOT);

		if (key.isBlank()) {
			return null;
		}

		Entry byName = bans.get(key);

		if (byName != null) {
			return byName;
		}

		for (Entry entry : bans.values()) {
			if (!entry.uuid.isBlank() && entry.uuid.equals(key)) {
				return entry;
			}
		}

		return null;
	}

	/** The active ban for a name or UUID, expiry applied; null when clear. */
	public Entry activeBan(String reference, long now) {
		Entry entry = find(reference);

		if (entry == null) {
			return null;
		}

		if (entry.expiresAt > 0 && entry.expiresAt <= now) {
			// expired: drop it lazily so the list stays honest without a sweeper
			remove(entry.usernameLower);
			return null;
		}

		return entry;
	}

	/** First enforcement point: the name, before anything else spends work on it. */
	@Subscribe(order = PostOrder.FIRST)
	public void onPreLogin(PreLoginEvent event) {
		if (!event.getResult().isAllowed()) {
			return;
		}

		long now = System.currentTimeMillis();
		Entry entry = activeBan(event.getUsername(), now);

		if (entry == null) {
			return;
		}

		recordHit(entry, now);
		event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
			NetworkBanMessages.playerBanned(entry.reason, entry.expiresAt)
		));
	}

	/** Second enforcement point: the UUID, which outlives a rename. */
	@Subscribe(order = PostOrder.FIRST)
	public void onLogin(LoginEvent event) {
		if (!event.getResult().isAllowed()) {
			return;
		}

		long now = System.currentTimeMillis();
		Entry entry = activeBan(event.getPlayer().getUniqueId().toString(), now);

		if (entry == null) {
			return;
		}

		recordHit(entry, now);
		event.setResult(ResultedEvent.ComponentResult.denied(
			NetworkBanMessages.playerBanned(entry.reason, entry.expiresAt)
		));
	}

	/** Count a refused attempt; persisted at most once a minute per ban. */
	private void recordHit(Entry entry, long now) {
		entry.hits += 1;
		entry.lastHitAt = now;

		if (now - entry.lastPersistedHitAt < HIT_PERSIST_INTERVAL_MS) {
			return;
		}

		entry.lastPersistedHitAt = now;
		submit("record-hit", () -> database.update(
			"UPDATE luna_network_bans SET hits = ?, last_hit_at = ? WHERE username_lower = ?",
			List.of(entry.hits, entry.lastHitAt, entry.usernameLower)
		));
	}

	/** A plausible Minecraft username (Java names; a Bedrock prefix allowed). */
	public static boolean validUsername(String name) {
		String trimmed = name == null ? "" : name.trim();
		return trimmed.matches("^[.*]?[A-Za-z0-9_]{1,16}$");
	}

	private Entry fromRow(Map<String, Object> row) {
		return new Entry(
			stringOf(row.get("username")),
			stringOf(row.get("uuid")),
			stringOf(row.get("reason")),
			stringOf(row.get("actor")),
			longOf(row.get("created_at")),
			longOf(row.get("expires_at")),
			longOf(row.get("hits")),
			longOf(row.get("last_hit_at"))
		);
	}

	private String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value);
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
					logger.error("Ghi lệnh cấm người chơi toàn mạng thất bại (" + what + "): " + exception.getMessage(), exception);
				}
			});
		} catch (RejectedExecutionException shuttingDown) {
			// Writes after shutdown are dropped on purpose; the proxy is going away.
		}
	}
}
