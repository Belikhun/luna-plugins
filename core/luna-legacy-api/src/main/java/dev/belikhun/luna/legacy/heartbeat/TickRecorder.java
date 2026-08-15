package dev.belikhun.luna.legacy.heartbeat;

/**
 * Counts what every tick cost, so the heartbeat can report an index rather than
 * one more instantaneous number.
 *
 * The Java 8 twin of the modern api's recorder, kept identical on purpose: the
 * two produce the same index for the same ticks, so a 1.12 backend and a 1.21
 * one can sit in the same column of the same table.
 *
 * Deliberately platform-free: the loaders disagree about what a tick event is
 * called and about half of them have no event at all, but they can all produce a
 * duration in milliseconds, and that is the entire input. Each platform's own
 * hook calls {@link #record}; everything above this line is arithmetic.
 *
 * The window is a ring of one-second buckets rather than a list of ticks. Twenty
 * ticks a second over a minute is twelve hundred samples, and keeping them all to
 * compute two ratios would be storing a log to answer a question about its
 * shape. A bucket per second holds counters only, so the memory is fixed however
 * long the server runs, and a bucket older than the window is simply overwritten
 * when its slot comes round again.
 *
 * {@link #record} runs on the server thread and {@link #snapshot} on whichever
 * thread publishes the heartbeat, so both are synchronized. The contention is
 * twenty short critical sections a second against one every few seconds.
 */
public final class TickRecorder {
	/** A tick at 20 TPS is 50 ms, so that is the Apdex target by arithmetic. */
	public static final double SATISFIED_MILLIS = 50D;

	/** Apdex's own convention: tolerating ends at four times the target. */
	public static final double TOLERATING_MILLIS = SATISFIED_MILLIS * 4D;

	/**
	 * Above this a tick is slow enough for a player to notice, so it counts
	 * against everyone who was online for it. Two ticks' worth of budget: a
	 * single one is a stutter, a run of them is what people log off over.
	 */
	public static final double MISERY_MILLIS = 100D;

	/**
	 * Seconds of history kept.
	 *
	 * Long enough that one garbage-collection pause does not define the whole
	 * reading, short enough that the number still describes the server as it is
	 * now rather than as it was during the last event.
	 */
	private static final int WINDOW_SECONDS = 60;

	private static final class Bucket {
		long stamp = Long.MIN_VALUE;
		long satisfied;
		long tolerating;
		long frustrated;
		long playerTicks;
		long miserablePlayerTicks;
		double totalMillis;
		double maxMillis;

		void reuse(long second) {
			stamp = second;
			satisfied = 0L;
			tolerating = 0L;
			frustrated = 0L;
			playerTicks = 0L;
			miserablePlayerTicks = 0L;
			totalMillis = 0D;
			maxMillis = 0D;
		}
	}

	private final Bucket[] ring = new Bucket[WINDOW_SECONDS];

	public TickRecorder() {
		for (int index = 0; index < ring.length; index++) {
			ring[index] = new Bucket();
		}
	}

	/**
	 * Fold one tick into the window.
	 *
	 * @param millis        how long the tick took
	 * @param onlinePlayers who was on to feel it; zero means nobody was
	 */
	public synchronized void record(double millis, int onlinePlayers) {
		if (!(millis >= 0D) || Double.isInfinite(millis)) {
			return;
		}

		long second = System.currentTimeMillis() / 1000L;
		Bucket bucket = ring[(int) Math.floorMod(second, WINDOW_SECONDS)];

		if (bucket.stamp != second) {
			bucket.reuse(second);
		}

		if (millis <= SATISFIED_MILLIS) {
			bucket.satisfied++;
		} else if (millis <= TOLERATING_MILLIS) {
			bucket.tolerating++;
		} else {
			bucket.frustrated++;
		}

		int players = Math.max(0, onlinePlayers);
		bucket.playerTicks += players;

		if (millis > MISERY_MILLIS) {
			bucket.miserablePlayerTicks += players;
		}

		bucket.totalMillis += millis;
		bucket.maxMillis = Math.max(bucket.maxMillis, millis);
	}

	/** The window as it stands, or {@link ServerTickStats#UNKNOWN} when empty. */
	public synchronized ServerTickStats snapshot() {
		long oldest = (System.currentTimeMillis() / 1000L) - WINDOW_SECONDS;

		long satisfied = 0L;
		long tolerating = 0L;
		long frustrated = 0L;
		long playerTicks = 0L;
		long miserablePlayerTicks = 0L;
		double totalMillis = 0D;
		double maxMillis = 0D;

		for (Bucket bucket : ring) {
			if (bucket.stamp < oldest) {
				continue;
			}

			satisfied += bucket.satisfied;
			tolerating += bucket.tolerating;
			frustrated += bucket.frustrated;
			playerTicks += bucket.playerTicks;
			miserablePlayerTicks += bucket.miserablePlayerTicks;
			totalMillis += bucket.totalMillis;
			maxMillis = Math.max(maxMillis, bucket.maxMillis);
		}

		long samples = satisfied + tolerating + frustrated;

		if (samples <= 0L) {
			return ServerTickStats.UNKNOWN;
		}

		double apdex = (satisfied + (tolerating / 2D)) / samples;
		// -1 rather than 0: an empty server did not make anybody miserable, but it
		// did not prove it could stay fast under load either
		double misery = playerTicks > 0L ? (double) miserablePlayerTicks / playerTicks : -1D;

		return new ServerTickStats(samples, totalMillis / samples, maxMillis, apdex, misery);
	}
}
