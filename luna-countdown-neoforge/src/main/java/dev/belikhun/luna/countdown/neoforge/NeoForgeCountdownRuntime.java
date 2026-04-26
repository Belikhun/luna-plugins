package dev.belikhun.luna.countdown.neoforge;

import java.util.List;

public interface NeoForgeCountdownRuntime extends AutoCloseable {
	int start(String title, int seconds);

	boolean stop(int id, String reason);

	void stopAll(String reason);

	List<CountdownSnapshot> activeCountdowns();

	@Override
	void close();
}
