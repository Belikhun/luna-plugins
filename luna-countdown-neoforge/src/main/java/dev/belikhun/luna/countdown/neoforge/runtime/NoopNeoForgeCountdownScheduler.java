package dev.belikhun.luna.countdown.neoforge.runtime;

final class NoopNeoForgeCountdownScheduler implements NeoForgeCountdownScheduler {
	@Override
	public NeoForgeScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis) {
		return () -> {
		};
	}
}
