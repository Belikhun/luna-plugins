package dev.belikhun.luna.legacy.countdown;

/** A repeating job that can be stopped. */
public interface ScheduledTask {
	void cancel();
}
