package dev.belikhun.luna.legacy.countdown;

/**
 * One countdown, as it stands right now.
 *
 * A record on the modern line; a final class here, because Java 8 bytecode
 * cannot carry `java.lang.Record`. Same fields, same immutability, same
 * accessor names, so a call site reads identically on both.
 */
public final class CountdownSnapshot {
	private final int id;
	private final String title;
	private final int totalSeconds;
	private final double remainingSeconds;
	private final boolean completed;

	public CountdownSnapshot(int id, String title, int totalSeconds, double remainingSeconds, boolean completed) {
		this.id = id;
		this.title = title;
		this.totalSeconds = totalSeconds;
		this.remainingSeconds = remainingSeconds;
		this.completed = completed;
	}

	public int id() {
		return id;
	}

	public String title() {
		return title;
	}

	public int totalSeconds() {
		return totalSeconds;
	}

	public double remainingSeconds() {
		return remainingSeconds;
	}

	public boolean completed() {
		return completed;
	}
}
