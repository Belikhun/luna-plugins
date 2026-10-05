package dev.belikhun.luna.core.api.placeholder;

/**
 * One world's weather and clock as last sampled.
 *
 * @param name          the world's name as the platform knows it
 * @param raining       whether it is raining
 * @param thundering    whether it is thundering
 * @param durationTicks ticks until the current weather changes, 0 when unknown
 * @param dayTimeTicks  ticks into the current day, 0 to 23999
 */
public record WorldWeather(String name, boolean raining, boolean thundering, long durationTicks, long dayTimeTicks) {
	/** A world without a clock reading. */
	public WorldWeather(String name, boolean raining, boolean thundering, long durationTicks) {
		this(name, raining, thundering, durationTicks, 0L);
	}
}
