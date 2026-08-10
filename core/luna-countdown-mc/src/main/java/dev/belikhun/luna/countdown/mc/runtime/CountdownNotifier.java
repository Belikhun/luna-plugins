package dev.belikhun.luna.countdown.mc.runtime;

import dev.belikhun.luna.countdown.mc.model.CountdownSnapshot;

public interface CountdownNotifier {
	void begin(CountdownSnapshot snapshot);

	void update(CountdownSnapshot snapshot);

	void complete(CountdownSnapshot snapshot);

	void cancelled(CountdownSnapshot snapshot, String reason);
}
