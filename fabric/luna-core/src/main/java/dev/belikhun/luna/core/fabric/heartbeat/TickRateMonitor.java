package dev.belikhun.luna.core.fabric.heartbeat;

/**
 * Ticks per second, measured rather than asked for.
 *
 * The server's own tick-time accessor has been renamed twice inside the range
 * this mod supports ({@code getAverageTickTime} on 1.20, {@code getAverageTickTimeNanos}
 * from 1.20.5), and a compiled call to either one breaks on the versions that
 * have the other. Counting the ticks luna is already being told about needs no
 * game method at all, so it reads the same on every version - and spark, where
 * it is installed, still takes precedence in the heartbeat.
 */
public final class TickRateMonitor {
	/** Five seconds of ticks: long enough to be steady, short enough to react. */
	private static final int WINDOW = 100;

	private final long[] tickNanos = new long[WINDOW];

	private int samples;
	private int cursor;

	/** Record one server tick. Called on the server thread only. */
	public void onTick() {
		tickNanos[cursor] = System.nanoTime();
		cursor = (cursor + 1) % WINDOW;
		if (samples < WINDOW) {
			samples++;
		}
	}

	/**
	 * The measured rate, capped at 20. Reports 20 until the window has enough
	 * samples to mean anything, which covers the first second after boot.
	 */
	public double tps() {
		int available = samples;
		if (available < 2) {
			return 20D;
		}

		int newestIndex = Math.floorMod(cursor - 1, WINDOW);
		int oldestIndex = available < WINDOW ? 0 : cursor;
		long elapsedNanos = tickNanos[newestIndex] - tickNanos[oldestIndex];

		if (elapsedNanos <= 0L) {
			return 20D;
		}

		double seconds = elapsedNanos / 1_000_000_000D;

		return Math.max(0D, Math.min(20D, (available - 1) / seconds));
	}
}
