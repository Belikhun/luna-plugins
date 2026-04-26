package dev.belikhun.luna.countdown.neoforge;

public interface NeoForgeCountdownNotifier {
	void begin(CountdownSnapshot snapshot);

	void update(CountdownSnapshot snapshot);

	void complete(CountdownSnapshot snapshot);

	void cancelled(CountdownSnapshot snapshot, String reason);
}
