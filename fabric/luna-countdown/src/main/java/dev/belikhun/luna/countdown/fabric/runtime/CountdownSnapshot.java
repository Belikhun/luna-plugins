package dev.belikhun.luna.countdown.fabric.runtime;

/** One running countdown as it stood at the moment it was read. */
public record CountdownSnapshot(
	int id,
	String title,
	int totalSeconds,
	double remainingSeconds
) {
}
