package dev.belikhun.luna.legacy.countdown;

/**
 * How a countdown reaches players.
 *
 * The whole reason the runtime beside this is platform-free: everything it does
 * is arithmetic and bookkeeping, and this interface is the one seam where a
 * server version matters.
 */
public interface CountdownNotifier {
	void begin(CountdownSnapshot snapshot);

	void update(CountdownSnapshot snapshot);

	void complete(CountdownSnapshot snapshot);

	void cancelled(CountdownSnapshot snapshot, String reason);
}
