package dev.belikhun.luna.legacy.heartbeat;

/**
 * How long the server's ticks took, and how much of that the players felt.
 *
 * The Java 8 twin of the modern api's record; see that one for what the two
 * indices mean and why they exist beside TPS. {@code samples} of zero means the
 * window holds no measurement at all, and every reading is then {@code -1}:
 * absent, not zero.
 */
public final class ServerTickStats {
	/** Nothing measured; what a platform that cannot time its ticks reports. */
	public static final ServerTickStats UNKNOWN = new ServerTickStats(0L, -1D, -1D, -1D, -1D);

	private final long samples;
	private final double meanMillis;
	private final double maxMillis;
	private final double apdex;
	private final double misery;

	public ServerTickStats(long samples, double meanMillis, double maxMillis, double apdex, double misery) {
		this.samples = Math.max(0L, samples);
		this.meanMillis = meanMillis;
		this.maxMillis = maxMillis;
		this.apdex = apdex;
		this.misery = misery;
	}

	public long samples() {
		return samples;
	}

	public double meanMillis() {
		return meanMillis;
	}

	public double maxMillis() {
		return maxMillis;
	}

	public double apdex() {
		return apdex;
	}

	public double misery() {
		return misery;
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
