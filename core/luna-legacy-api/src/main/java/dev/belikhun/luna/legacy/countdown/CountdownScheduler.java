package dev.belikhun.luna.legacy.countdown;

/**
 * Where a countdown's tick runs.
 *
 * An interface rather than a thread pool, because the platform decides: a tick
 * that sends a message to every player has to reach them from somewhere the
 * server is willing to be called from.
 */
public interface CountdownScheduler {
	ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis);
}
