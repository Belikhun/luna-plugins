package dev.belikhun.luna.countdown.neoforge.runtime;

import dev.belikhun.luna.countdown.neoforge.model.CountdownSnapshot;

final class NoopNeoForgeCountdownNotifier implements NeoForgeCountdownNotifier {
	@Override
	public void begin(CountdownSnapshot snapshot) {
	}

	@Override
	public void update(CountdownSnapshot snapshot) {
	}

	@Override
	public void complete(CountdownSnapshot snapshot) {
	}

	@Override
	public void cancelled(CountdownSnapshot snapshot, String reason) {
	}
}
