package dev.belikhun.luna.legacy.heartbeat;

import java.util.Arrays;

/**
 * What a backend reports about itself each heartbeat.
 *
 * A record in the modern api. The component order is the constructor's contract and
 * nothing else, but the *names* are the wire contract - `HeartbeatFormCodec` writes
 * them as form keys the proxy reads back by name, so renaming one here silently
 * changes what a 1.12.2 backend reports.
 */
public final class BackendHeartbeatStats {
	private final String software;
	private final String version;
	private final int serverPort;
	private final long uptimeMillis;
	private final double tps;
	private final int onlinePlayers;
	private final int maxPlayers;
	private final String motd;
	private final boolean whitelistEnabled;
	private final double systemCpuUsagePercent;
	private final double processCpuUsagePercent;
	private final long ramUsedBytes;
	private final long ramFreeBytes;
	private final long ramMaxBytes;
	private final long heartbeatLatencyMillis;

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
		this.software = software;
		this.version = version;
		this.serverPort = serverPort;
		this.uptimeMillis = uptimeMillis;
		this.tps = tps;
		this.onlinePlayers = onlinePlayers;
		this.maxPlayers = maxPlayers;
		this.motd = motd;
		this.whitelistEnabled = whitelistEnabled;
		this.systemCpuUsagePercent = systemCpuUsagePercent;
		this.processCpuUsagePercent = processCpuUsagePercent;
		this.ramUsedBytes = ramUsedBytes;
		this.ramFreeBytes = ramFreeBytes;
		this.ramMaxBytes = ramMaxBytes;
		this.heartbeatLatencyMillis = heartbeatLatencyMillis;
	}

	public String software() {
		return software;
	}

	public String version() {
		return version;
	}

	public int serverPort() {
		return serverPort;
	}

	public long uptimeMillis() {
		return uptimeMillis;
	}

	public double tps() {
		return tps;
	}

	public int onlinePlayers() {
		return onlinePlayers;
	}

	public int maxPlayers() {
		return maxPlayers;
	}

	public String motd() {
		return motd;
	}

	public boolean whitelistEnabled() {
		return whitelistEnabled;
	}

	public double systemCpuUsagePercent() {
		return systemCpuUsagePercent;
	}

	public double processCpuUsagePercent() {
		return processCpuUsagePercent;
	}

	public long ramUsedBytes() {
		return ramUsedBytes;
	}

	public long ramFreeBytes() {
		return ramFreeBytes;
	}

	public long ramMaxBytes() {
		return ramMaxBytes;
	}

	public long heartbeatLatencyMillis() {
		return heartbeatLatencyMillis;
	}

	private Object[] components() {
		return new Object[] {
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
			heartbeatLatencyMillis
		};
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof BackendHeartbeatStats)) {
			return false;
		}

		return Arrays.equals(components(), ((BackendHeartbeatStats) other).components());
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(components());
	}

	@Override
	public String toString() {
		return "BackendHeartbeatStats" + Arrays.toString(components());
	}
}
