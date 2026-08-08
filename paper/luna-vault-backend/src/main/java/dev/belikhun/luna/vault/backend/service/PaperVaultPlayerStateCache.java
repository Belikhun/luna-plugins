package dev.belikhun.luna.vault.backend.service;

import dev.belikhun.luna.vault.api.VaultCacheRefresh;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperVaultPlayerStateCache {
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
