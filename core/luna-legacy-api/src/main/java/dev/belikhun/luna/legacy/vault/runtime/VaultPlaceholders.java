package dev.belikhun.luna.legacy.vault.runtime;

import java.util.Collections;

import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;

import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.placeholder.LunaPlaceholderExtension;
import dev.belikhun.luna.legacy.vault.VaultMoney;
import dev.belikhun.luna.legacy.vault.VaultPlayerSnapshot;
import dev.belikhun.luna.legacy.vault.runtime.VaultGateway;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code %lunavault_balance%} and {@code %lunavault_rank%}.
 *
 * A placeholder is resolved while a tab list or a scoreboard is being written,
 * which cannot wait on the proxy. So it answers from a short-lived cache and
 * starts a refresh in the background when that cache goes stale - the same
 * arrangement the Paper expansion makes, and for the same reason.
 */
public final class VaultPlaceholders<P> implements LunaPlaceholderExtension<P> {
	private static final String NAMESPACE = "lunavault";
	private static final int MAX_CACHE_ENTRIES = 4096;

	private final VaultGateway<P> gateway;
	private final PlayerBridge<P> players;
	private final YamlConfigFile coreConfig;
	private final long refreshIntervalMillis;
	private final Map<UUID, CachedSnapshot> snapshotCache;
	private final Set<UUID> refreshInFlight;

	public VaultPlaceholders(VaultGateway<P> gateway, PlayerBridge<P> players, YamlConfigFile coreConfig, long timeoutMillis) {
		this.gateway = gateway;
		this.players = players;
		this.coreConfig = coreConfig;

		long normalizedTimeout = Math.max(1000L, timeoutMillis);

		this.refreshIntervalMillis = Math.min(5000L, Math.max(500L, normalizedTimeout / 2L));
		this.snapshotCache = new ConcurrentHashMap<>();
		this.refreshInFlight = ConcurrentHashMap.newKeySet();
	}

	@Override
	public Set<String> namespaces() {
		return Collections.singleton(NAMESPACE);
	}

	@Override
	public String resolve(P player, String namespace, String params) {
		if (player == null || params == null) {
			return null;
		}

		if (!params.equals("balance") && !params.equals("rank")) {
			return null;
		}

		UUID playerId = players.idOf(player);
		String playerName = players.nameOf(player);
		long now = System.currentTimeMillis();
		CachedSnapshot cached = snapshotCache.get(playerId);

		if (cached != null) {
			if (now - cached.cachedAtMillis() > refreshIntervalMillis) {
				refreshSnapshotAsync(playerId, playerName);
			}

			return formatValue(params, cached.snapshot());
		}

		VaultPlayerSnapshot immediate = gateway.cachedSnapshot(playerId, playerName);

		if (immediate.playerId() != null) {
			cache(playerId, immediate);
			return formatValue(params, immediate);
		}

		refreshSnapshotAsync(playerId, playerName);
		return fallbackValue(params);
	}

	@Override
	public void contributeSnapshot(P player, Map<String, String> values) {
		if (player == null) {
			return;
		}

		values.put(NAMESPACE + "_balance", resolve(player, NAMESPACE, "balance"));
		values.put(NAMESPACE + "_rank", resolve(player, NAMESPACE, "rank"));
	}

	public void forget(UUID playerId) {
		if (playerId == null) {
			return;
		}

		snapshotCache.remove(playerId);
		refreshInFlight.remove(playerId);
	}

	private String formatValue(String params, VaultPlayerSnapshot snapshot) {
		if (params.equals("rank")) {
			return String.valueOf(snapshot.rank());
		}

		return Formatters.money(coreConfig, snapshot.balanceMinor(), VaultMoney.SCALE);
	}

	private String fallbackValue(String params) {
		if (params.equals("rank")) {
			return "0";
		}

		return Formatters.money(coreConfig, 0D);
	}

	private void refreshSnapshotAsync(UUID playerId, String playerName) {
		if (!refreshInFlight.add(playerId)) {
			return;
		}

		gateway.snapshot(playerId, playerName).whenComplete((snapshot, throwable) -> {
			try {
				if (throwable == null && snapshot != null && snapshot.playerId() != null) {
					cache(playerId, snapshot);
				}
			} finally {
				refreshInFlight.remove(playerId);
			}
		});
	}

	private void cache(UUID playerId, VaultPlayerSnapshot snapshot) {
		if (snapshotCache.size() >= MAX_CACHE_ENTRIES) {
			snapshotCache.clear();
		}

		snapshotCache.put(playerId, new CachedSnapshot(snapshot, System.currentTimeMillis()));
	}

	private static final class CachedSnapshot {
		private final VaultPlayerSnapshot snapshot;
		private final long cachedAtMillis;

		private CachedSnapshot(VaultPlayerSnapshot snapshot, long cachedAtMillis) {
			this.snapshot = snapshot;
			this.cachedAtMillis = cachedAtMillis;
		}

		private VaultPlayerSnapshot snapshot() {
			return snapshot;
		}

		private long cachedAtMillis() {
			return cachedAtMillis;
		}
	}
}
