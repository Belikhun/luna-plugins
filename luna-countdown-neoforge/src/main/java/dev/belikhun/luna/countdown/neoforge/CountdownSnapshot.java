package dev.belikhun.luna.countdown.neoforge;

public record CountdownSnapshot(
	int id,
	String title,
	int totalSeconds,
	double remainingSeconds,
	boolean completed
) {
}
