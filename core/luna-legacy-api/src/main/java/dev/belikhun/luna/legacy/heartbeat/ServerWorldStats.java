package dev.belikhun.luna.legacy.heartbeat;

/**
 * What one world is holding: the chunks it has loaded and the entities in them.
 *
 * The Java 8 twin of the modern api's record. The *names* are the wire contract,
 * as with {@link BackendHeartbeatStats}: the proxy reads these back by form key,
 * so the two classes have to agree on them however differently they are written.
 *
 * On 1.12 a loaded chunk is a ticking chunk - the game has no unsimulated middle
 * ground for the newer lines to distinguish - so everything a 1.12 backend
 * reports lands in {@link #tickingEntities()} and the other stays zero. That is
 * true for this version rather than a gap in the reporting.
 */
public final class ServerWorldStats {
	/** A counter the platform could not measure; not the same as none. */
	public static final int UNKNOWN = -1;

	private final String name;
	private final int loadedChunks;
	private final int tickingEntities;
	private final int nonTickingEntities;

	public ServerWorldStats(String name, int loadedChunks, int tickingEntities, int nonTickingEntities) {
		this.name = name == null ? "" : name;
		this.loadedChunks = Math.max(UNKNOWN, loadedChunks);
		this.tickingEntities = Math.max(UNKNOWN, tickingEntities);
		this.nonTickingEntities = Math.max(UNKNOWN, nonTickingEntities);
	}

	public String name() {
		return name;
	}

	public int loadedChunks() {
		return loadedChunks;
	}

	public int tickingEntities() {
		return tickingEntities;
	}

	public int nonTickingEntities() {
		return nonTickingEntities;
	}

	/** Everything in the world's loaded chunks, or {@link #UNKNOWN} if neither half was measured. */
	public int entities() {
		if (tickingEntities < 0 && nonTickingEntities < 0) {
			return UNKNOWN;
		}

		return Math.max(0, tickingEntities) + Math.max(0, nonTickingEntities);
	}
}
