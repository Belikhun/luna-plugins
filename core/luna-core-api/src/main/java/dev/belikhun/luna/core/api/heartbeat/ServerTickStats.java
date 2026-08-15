package dev.belikhun.luna.core.api.heartbeat;

/**
 * How long the server's ticks took, and how much of that the players felt.
 *
 * TPS answers "is it keeping up" and nothing else: it is an average that is
 * pinned at 20 until things are already bad, and it says the same thing about a
 * server holding a steady 45 ms as about one alternating 10 ms and 90 ms. These
 * two indices are here because they say what TPS cannot.
 *
 * <p><b>Apdex</b> is the standard application performance index applied to
 * ticks: satisfied plus half the tolerating, over the total. The thresholds are
 * arithmetic rather than taste - 20 TPS is a 50 ms tick, so 50 ms is the target,
 * and Apdex's own convention puts the tolerating ceiling at four times it.
 *
 * <p><b>Misery</b> is player-weighted, which is the part Apdex misses. A slow
 * tick on an empty server inconvenienced nobody; the same tick with forty people
 * on cost forty people. It is the share of observed player-time that elapsed
 * during ticks slow enough to be felt, so it is only defined while somebody is
 * online - {@code -1} says nobody was, rather than claiming a perfect score for
 * an empty server.
 *
 * <p>{@code samples} of zero means the window holds no measurement at all, which
 * is what a platform with no tick hook reports. Every reading is then {@code -1}:
 * absent, not zero.
 */
public record ServerTickStats(
	long samples,
	double meanMillis,
	double maxMillis,
	double apdex,
	double misery
) {
	/** Nothing measured; what a platform that cannot time its ticks reports. */
	public static final ServerTickStats UNKNOWN = new ServerTickStats(0L, -1D, -1D, -1D, -1D);

	public ServerTickStats {
		samples = Math.max(0L, samples);
	}

	/** Whether the window holds any measured tick. */
	public boolean known() {
		return samples > 0L;
	}

	/** Whether anybody was online to be made miserable. */
	public boolean miseryKnown() {
		return misery >= 0D;
	}
}
