package dev.belikhun.luna.legacy.vault;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A backend's local copy of what the proxy last said each player's balance was.
 *
 * Every gateway keeps one: a balance is asked for far more often than it
 * changes - a scoreboard, a tab list and a shop title all want it every tick -
 * and the proxy pushes a refresh when it moves. The cap is a guard against a
 * long-running backend accumulating entries for players who never come back;
 * clearing wholesale is cheaper than evicting, because the next lookup for a
 * player who is still online just refills their entry.
 */
public final class VaultPlayerStateCache {
	private static final int MAX_ENTRIES = 4096;

	private final Map<UUID, VaultPlayerSnapshot> snapshots = new ConcurrentHashMap<>();

	public VaultPlayerSnapshot get(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		return snapshots.get(playerId);
	}

	public void put(VaultPlayerSnapshot snapshot) {
		if (snapshot == null || snapshot.playerId() == null) {
			return;
		}
		snapshots.put(snapshot.playerId(), snapshot);
		trimIfNeeded();
	}

	public void remove(UUID playerId) {
		if (playerId == null) {
			return;
		}
		snapshots.remove(playerId);
	}

	public void apply(VaultCacheRefresh refresh) {
		if (refresh == null) {
			return;
		}

		if (refresh.clearAll()) {
			snapshots.clear();
		}

		for (VaultPlayerSnapshot snapshot : refresh.snapshots()) {
			put(snapshot);
		}
	}

	private void trimIfNeeded() {
		if (snapshots.size() <= MAX_ENTRIES) {
			return;
		}

		snapshots.clear();
	}
}
