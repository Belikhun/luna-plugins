package dev.belikhun.luna.countdown.neoforge;

public interface NeoForgeCountdownScheduler {
	NeoForgeScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis);
}
