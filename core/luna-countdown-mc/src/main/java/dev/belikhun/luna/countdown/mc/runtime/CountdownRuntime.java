package dev.belikhun.luna.countdown.mc.runtime;

import dev.belikhun.luna.countdown.mc.model.CountdownSnapshot;

import java.util.List;

public interface CountdownRuntime extends AutoCloseable {
	int start(String title, int seconds);

	boolean stop(int id, String reason);

	void stopAll(String reason);

	List<CountdownSnapshot> activeCountdowns();

	@Override
	void close();
}
