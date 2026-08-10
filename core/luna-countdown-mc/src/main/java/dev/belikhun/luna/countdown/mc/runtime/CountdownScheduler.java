package dev.belikhun.luna.countdown.mc.runtime;

public interface CountdownScheduler {
	ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis);
}
