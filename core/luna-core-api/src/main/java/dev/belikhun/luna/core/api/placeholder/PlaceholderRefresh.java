package dev.belikhun.luna.core.api.placeholder;

/**
 * How often a placeholder's value can change, which is what a consumer that
 * polls (TAB, the tab bridge) uses to pick its interval.
 */
public enum PlaceholderRefresh {
	/** Never changes while the server runs: version, backend name. */
	STATIC(-1L),
	/** Changes with the server sample: TPS, CPU, entity counts. */
	SERVER(1000L),
	/** Changes with the player sample: ping, position, level. */
	PLAYER(500L),
	/** Recomputed on every request; the resolver reads something the samples do not carry. */
	LIVE(100L);

	private final long suggestedIntervalMillis;

	PlaceholderRefresh(long suggestedIntervalMillis) {
		this.suggestedIntervalMillis = suggestedIntervalMillis;
	}

	/** The polling interval a consumer should not go below, or -1 for never. */
	public long suggestedIntervalMillis() {
		return suggestedIntervalMillis;
	}
}
