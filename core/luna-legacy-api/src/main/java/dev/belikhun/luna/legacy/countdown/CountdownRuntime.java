package dev.belikhun.luna.legacy.countdown;

import java.util.List;

/** Start, stop and list the countdowns running on one server. */
public interface CountdownRuntime extends AutoCloseable {
	/** @return the new countdown's id */
	int start(String title, int seconds);

	/** @return whether a countdown with that id was running */
	boolean stop(int id, String reason);

	void stopAll(String reason);

	List<CountdownSnapshot> activeCountdowns();

	@Override
	void close();
}
