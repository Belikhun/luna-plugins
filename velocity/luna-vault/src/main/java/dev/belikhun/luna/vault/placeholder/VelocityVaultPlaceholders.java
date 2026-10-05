package dev.belikhun.luna.vault.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRefresh;
import dev.belikhun.luna.core.api.placeholder.PlaceholderScope;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSpec;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.core.velocity.placeholder.VelocityPlaceholderModule;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code %lunav_vault_balance%} and {@code %lunav_vault_rank%} on the proxy.
 *
 * A TAB refresh runs on TAB's thread and a MiniMessage tag resolves inside the
 * chat pipeline; neither may wait on the ledger. Values come from a short cache
 * refreshed in the background, so the ledger is read at most once per player
 * per interval and never on the caller's thread.
 */
public final class VelocityVaultPlaceholders {
	private static final long REFRESH_INTERVAL_MILLIS = 2_000L;
	private static final int MAX_CACHE_ENTRIES = 4_096;

	private final VelocityVaultService vaultService;
	private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public VelocityVaultPlaceholders(VelocityVaultService vaultService) {
		this.vaultService = vaultService;
	}

	/** Publishes the specs and keeps the pre-rename TAB ids answering. */
	public void register(VelocityPlaceholderModule module) {
		module.aliasTabId("%lunavaultv-balance%", "%lunav_vault_balance%");
		module.aliasTabId("%lunavaultv-rank%", "%lunav_vault_rank%");
		module.runtime().register(this, specs());
	}

	public void unregister(VelocityPlaceholderModule module) {
		module.runtime().unregister(this);
		cache.clear();
	}

	/** Drops a player's cached snapshot, on disconnect. */
	public void forget(UUID playerId) {
		if (playerId != null) {
			cache.remove(playerId);
			inFlight.remove(playerId);
		}
	}

	private List<PlaceholderSpec<Player>> specs() {
		return List.of(
			PlaceholderSpec.of("lunav", "vault_balance", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
				"The viewer's balance, formatted with the network currency",
				request -> LunaCoreVelocity.services().moneyFormat().formatMinor(snapshot(request.viewer()).balanceMinor(), VaultMoney.SCALE)),
			PlaceholderSpec.of("lunav", "vault_rank", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
				"The viewer's rank on the balance leaderboard",
				request -> request.viewer() == null ? "" : String.valueOf(snapshot(request.viewer()).rank()))
		);
	}

	private VaultPlayerSnapshot snapshot(PlayerSample viewer) {
		if (viewer == null || viewer.id() == null) {
			return VaultPlayerSnapshot.empty(null, "");
		}

		long now = System.currentTimeMillis();
		Cached cached = cache.get(viewer.id());

		if (cached == null || now - cached.at() > REFRESH_INTERVAL_MILLIS) {
			refresh(viewer.id(), viewer.name());
		}

		return cached == null ? VaultPlayerSnapshot.empty(viewer.id(), viewer.name()) : cached.snapshot();
	}

	private void refresh(UUID playerId, String playerName) {
		if (!inFlight.add(playerId)) {
			return;
		}

		vaultService.snapshot(playerId, playerName).whenComplete((snapshot, throwable) -> {
			try {
				if (throwable == null && snapshot != null) {
					if (cache.size() >= MAX_CACHE_ENTRIES) {
						cache.clear();
					}

					cache.put(playerId, new Cached(snapshot, System.currentTimeMillis()));
				}
			} finally {
				inFlight.remove(playerId);
			}
		});
	}

	private record Cached(VaultPlayerSnapshot snapshot, long at) {
	}
}
