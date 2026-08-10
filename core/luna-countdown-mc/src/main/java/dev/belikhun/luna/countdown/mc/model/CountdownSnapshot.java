package dev.belikhun.luna.countdown.mc.model;

public record CountdownSnapshot(
	int id,
	String title,
	int totalSeconds,
	double remainingSeconds,
	boolean completed
) {
}
