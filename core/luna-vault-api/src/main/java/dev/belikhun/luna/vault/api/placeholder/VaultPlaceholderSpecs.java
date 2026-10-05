package dev.belikhun.luna.vault.api.placeholder;

import dev.belikhun.luna.core.api.placeholder.PlaceholderRefresh;
import dev.belikhun.luna.core.api.placeholder.PlaceholderScope;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSpec;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.client.VaultClient;

import java.util.List;
import java.util.function.LongFunction;

/**
 * {@code %lunavault_balance%} and {@code %lunavault_rank%}, the same on every
 * backend platform.
 *
 * A placeholder resolves while a tab list is being written and cannot wait on
 * the proxy, so it answers from the client's cache. Asking the client for a
 * snapshot returns the cached one at once and starts a refresh when the cache
 * has gone stale, which is all the background work these need.
 */
public final class VaultPlaceholderSpecs {
	/** The namespace on a backend, matching the old PlaceholderAPI expansion. */
	public static final String NAMESPACE = "lunavault";

	private VaultPlaceholderSpecs() {
	}

	/**
	 * The two specs.
	 *
	 * @param client the backend's vault client
	 * @param money  formats a minor-unit balance with the network's currency settings
	 * @param <P>    the client's player type
	 * @param <V>    the placeholder runtime's viewer type, which may differ from the
	 *               client's: Paper resolves for offline players but transacts for
	 *               online ones. Only the viewer sample is read, so both fit.
	 */
	public static <P, V> List<PlaceholderSpec<V>> specs(VaultClient<P> client, LongFunction<String> money) {
		return List.of(
			PlaceholderSpec.of(NAMESPACE, "balance", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
				"The viewer's balance, formatted",
				request -> money.apply(snapshot(client, request.viewer()).balanceMinor())),
			PlaceholderSpec.of(NAMESPACE, "rank", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
				"The viewer's rank on the balance leaderboard",
				request -> Integer.toString(snapshot(client, request.viewer()).rank()))
		);
	}

	private static <P> VaultPlayerSnapshot snapshot(VaultClient<P> client, PlayerSample viewer) {
		if (viewer == null || viewer.id() == null) {
			return VaultPlayerSnapshot.empty(null, "");
		}

		// returns the cached snapshot immediately and refreshes it when stale
		client.snapshot(viewer.id(), viewer.name());
		return client.cachedSnapshot(viewer.id(), viewer.name());
	}
}
