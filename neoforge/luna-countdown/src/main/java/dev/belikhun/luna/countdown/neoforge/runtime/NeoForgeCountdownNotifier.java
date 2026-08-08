package dev.belikhun.luna.countdown.neoforge.runtime;

import dev.belikhun.luna.countdown.neoforge.model.CountdownSnapshot;

public interface NeoForgeCountdownNotifier {
	void begin(CountdownSnapshot snapshot);

	void update(CountdownSnapshot snapshot);

	void complete(CountdownSnapshot snapshot);

	void cancelled(CountdownSnapshot snapshot, String reason);
}
