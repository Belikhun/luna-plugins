package dev.belikhun.luna.countdown.neoforge.runtime;

public interface NeoForgeCountdownScheduler {
	NeoForgeScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis);
}
