package dev.belikhun.luna.core.api.heartbeat;

/**
 * What one world is holding: the chunks it has loaded and the entities in them.
 *
 * Entities are split by whether they are actually being ticked. A chunk can be
 * loaded without being simulated - it is kept for a nearby player, or held by a
 * force-load, or sitting in the border ring around the ticking area - and the
 * mobs in it cost memory but no tick time. Reporting one total hides exactly the
 * distinction an operator is looking for when a server is slow: ten thousand
 * entities is fine if nine thousand of them are frozen.
 *
 * Not every platform can tell the two apart. One that cannot puts everything in
 * {@link #tickingEntities()} and leaves the other at zero, which is true for it:
 * on 1.12 a loaded chunk is a ticking chunk.
 *
 * A counter of {@code -1} means the platform could not measure that one at all,
 * which is a different statement from zero. Pumpkin's sandbox, for instance, can
 * list a world's entities but cannot ask how many chunks are loaded, so it
 * reports the entities it counted and {@code -1} chunks rather than claiming a
 * world with nothing in it.
 */
public record ServerWorldStats(
	String name,
	int loadedChunks,
	int tickingEntities,
	int nonTickingEntities
) {
	/** A counter the platform could not measure; not the same as none. */
	public static final int UNKNOWN = -1;

	public ServerWorldStats {
		name = name == null ? "" : name;
		loadedChunks = Math.max(UNKNOWN, loadedChunks);
		tickingEntities = Math.max(UNKNOWN, tickingEntities);
		nonTickingEntities = Math.max(UNKNOWN, nonTickingEntities);
	}

	/**
	 * Everything in the world's loaded chunks, ticking or not, or {@link #UNKNOWN}
	 * when neither half was measured. One measured half counts on its own: a
	 * platform reporting entities without the ticking split still knows the total.
	 */
	public int entities() {
		if (tickingEntities < 0 && nonTickingEntities < 0) {
			return UNKNOWN;
		}

		return Math.max(0, tickingEntities) + Math.max(0, nonTickingEntities);
	}
}
