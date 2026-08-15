package dev.belikhun.luna.core.api.heartbeat;

import java.util.List;

public record BackendHeartbeatStats(
	String software,
	String version,
	int serverPort,
	long uptimeMillis,
	double tps,
	int onlinePlayers,
	int maxPlayers,
	String motd,
	boolean whitelistEnabled,
	double systemCpuUsagePercent,
	double processCpuUsagePercent,
	long ramUsedBytes,
	long ramFreeBytes,
	long ramMaxBytes,
	long heartbeatLatencyMillis,
	List<ServerWorldStats> worlds,
	ServerTickStats ticks
) {
	public BackendHeartbeatStats {
		worlds = worlds == null ? List.of() : List.copyOf(worlds);
		ticks = ticks == null ? ServerTickStats.UNKNOWN : ticks;
	}

	/**
	 * The shape every caller used before worlds and ticks existed.
	 *
	 * Kept so a platform that has not been taught to report them says so by
	 * omission rather than by every call site growing two literal blanks.
	 */
	public BackendHeartbeatStats(
		String software,
		String version,
		int serverPort,
		long uptimeMillis,
		double tps,
		int onlinePlayers,
		int maxPlayers,
		String motd,
		boolean whitelistEnabled,
		double systemCpuUsagePercent,
		double processCpuUsagePercent,
		long ramUsedBytes,
		long ramFreeBytes,
		long ramMaxBytes,
		long heartbeatLatencyMillis
	) {
		this(
			software,
			version,
			serverPort,
			uptimeMillis,
			tps,
			onlinePlayers,
			maxPlayers,
			motd,
			whitelistEnabled,
			systemCpuUsagePercent,
			processCpuUsagePercent,
			ramUsedBytes,
			ramFreeBytes,
			ramMaxBytes,
			heartbeatLatencyMillis,
			List.of(),
			ServerTickStats.UNKNOWN
		);
	}

	/**
	 * The same reading, stamped with how long the heartbeat took to arrive.
	 *
	 * A method rather than a rebuild at the call site. The proxy has exactly one
	 * reason to copy this record and used to spell all fifteen components out to
	 * do it; when two more arrived, that copy kept compiling and silently dropped
	 * them, so every backend's worlds and ticks reached the registry empty. Any
	 * future component is carried through here for free.
	 */
	public BackendHeartbeatStats withLatency(long latencyMillis) {
		return new BackendHeartbeatStats(
			software,
			version,
			serverPort,
			uptimeMillis,
			tps,
			onlinePlayers,
			maxPlayers,
			motd,
			whitelistEnabled,
			systemCpuUsagePercent,
			processCpuUsagePercent,
			ramUsedBytes,
			ramFreeBytes,
			ramMaxBytes,
			Math.max(0L, latencyMillis),
			worlds,
			ticks
		);
	}

	/** Chunks loaded across every world, or {@link ServerWorldStats#UNKNOWN} when none could say. */
	public int loadedChunks() {
		int total = ServerWorldStats.UNKNOWN;

		for (ServerWorldStats world : worlds) {
			int value = world.loadedChunks();

			if (value < 0) {
				continue;
			}

			total = Math.max(0, total) + value;
		}

		return total;
	}

	/** Ticking entities across every world. */
	public int tickingEntities() {
		int total = ServerWorldStats.UNKNOWN;

		for (ServerWorldStats world : worlds) {
			int value = world.tickingEntities();

			if (value < 0) {
				continue;
			}

			total = Math.max(0, total) + value;
		}

		return total;
	}

	/** Loaded but unsimulated entities across every world. */
	public int nonTickingEntities() {
		int total = ServerWorldStats.UNKNOWN;

		for (ServerWorldStats world : worlds) {
			int value = world.nonTickingEntities();

			if (value < 0) {
				continue;
			}

			total = Math.max(0, total) + value;
		}

		return total;
	}
}
