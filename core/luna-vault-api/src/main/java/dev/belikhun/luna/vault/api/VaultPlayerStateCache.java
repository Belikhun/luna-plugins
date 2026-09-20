package dev.belikhun.luna.vault.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A backend's local copy of what the proxy last said each player's balance was.
 *
 * Every gateway keeps one: a balance is asked for far more often than it
 * changes; a scoreboard, a tab list and a shop title all want it every tick,
 * and the proxy pushes a refresh when it moves.
 *
 * Two rules keep it honest. A snapshot only replaces one of the same player
 * when its version is not older, so a push and a reply that crossed on the
 * wire settle on the newer value. And nothing here ever turns a known balance
 * into "unknown" on the proxy's behalf: a bulk invalidation marks entries
 * stale, and a stale entry is still served while a fresh one is fetched,
 * because the alternative is every player watching their money read zero.
 *
 * The cap guards a long-running backend against players who never come back;
 * when it is hit the whole map is dropped, since the next lookup for anyone
 * still online refills their entry at once.
 */
public final class VaultPlayerStateCache {
	private static final int MAX_ENTRIES = 4096;

	private final Map<UUID, VaultPlayerSnapshot> snapshots = new ConcurrentHashMap<>();
	private final Set<UUID> stale = ConcurrentHashMap.newKeySet();

	public VaultPlayerSnapshot get(UUID playerId) {
		if (playerId == null) {
			return null;
		}

		return snapshots.get(playerId);
	}

	/** Whether the held snapshot, if any, has been flagged as possibly outdated. */
	public boolean isStale(UUID playerId) {
		if (playerId == null) {
			return false;
		}

		return stale.contains(playerId);
	}

	/**
	 * Store a snapshot unless a newer one is already held.
	 *
	 * @return true when the cache now holds this snapshot
	 */
	public boolean put(VaultPlayerSnapshot snapshot) {
		if (snapshot == null || snapshot.playerId() == null) {
			return false;
		}

		VaultPlayerSnapshot stored = snapshots.merge(snapshot.playerId(), snapshot, (existing, incoming) ->
			incoming.supersedes(existing) ? incoming : existing
		);

		boolean accepted = stored == snapshot;

		if (accepted) {
			stale.remove(snapshot.playerId());
		}

		trimIfNeeded();

		return accepted;
	}

	public void remove(UUID playerId) {
		if (playerId == null) {
			return;
		}

		snapshots.remove(playerId);
		stale.remove(playerId);
	}

	public void apply(VaultCacheRefresh refresh) {
		if (refresh == null) {
			return;
		}

		if (refresh.clearAll()) {
			markAllStale();
		}

		for (VaultPlayerSnapshot snapshot : refresh.snapshots()) {
			put(snapshot);
		}
	}

	/** Flag every held entry as needing a refetch, keeping its value for display. */
	public void markAllStale() {
		stale.addAll(snapshots.keySet());
	}

	public int size() {
		return snapshots.size();
	}

	private void trimIfNeeded() {
		if (snapshots.size() <= MAX_ENTRIES) {
			return;
		}

		snapshots.clear();
		stale.clear();
	}
}
