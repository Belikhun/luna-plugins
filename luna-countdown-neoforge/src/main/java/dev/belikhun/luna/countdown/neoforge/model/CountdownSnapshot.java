package dev.belikhun.luna.countdown.neoforge.model;

public record CountdownSnapshot(
	int id,
	String title,
	int totalSeconds,
	double remainingSeconds,
	boolean completed
) {
}
