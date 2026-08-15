package dev.belikhun.luna.legacy.placeholder;

import java.util.Objects;

/** The server statistics one resolution round sees, fixed for its duration. */
public final class PlaceholderSnapshot {
	private final double currentTps;
	private final double currentTickDurationMillis;
	private final String sparkTickDuration10Sec;
	private final int playerPingMillis;
	private final long uptimeMillis;
	private final double systemCpuPercent;
	private final double processCpuPercent;
	private final long ramUsedBytes;
	private final long ramMaxBytes;
	private final int totalEntities;
	private final int totalLivingEntities;
	private final int totalChunks;

	public PlaceholderSnapshot(double currentTps, double currentTickDurationMillis, String sparkTickDuration10Sec, int playerPingMillis, long uptimeMillis, double systemCpuPercent, double processCpuPercent, long ramUsedBytes, long ramMaxBytes, int totalEntities, int totalLivingEntities, int totalChunks) {
		this.currentTps = currentTps;
		this.currentTickDurationMillis = currentTickDurationMillis;
		this.sparkTickDuration10Sec = sparkTickDuration10Sec;
		this.playerPingMillis = playerPingMillis;
		this.uptimeMillis = uptimeMillis;
		this.systemCpuPercent = systemCpuPercent;
		this.processCpuPercent = processCpuPercent;
		this.ramUsedBytes = ramUsedBytes;
		this.ramMaxBytes = ramMaxBytes;
		this.totalEntities = totalEntities;
		this.totalLivingEntities = totalLivingEntities;
		this.totalChunks = totalChunks;
	}

	public double currentTps() {
		return currentTps;
	}

	public double currentTickDurationMillis() {
		return currentTickDurationMillis;
	}

	public String sparkTickDuration10Sec() {
		return sparkTickDuration10Sec;
	}

	public int playerPingMillis() {
		return playerPingMillis;
	}

	public long uptimeMillis() {
		return uptimeMillis;
	}

	public double systemCpuPercent() {
		return systemCpuPercent;
	}

	public double processCpuPercent() {
		return processCpuPercent;
	}

	public long ramUsedBytes() {
		return ramUsedBytes;
	}

	public long ramMaxBytes() {
		return ramMaxBytes;
	}

	public int totalEntities() {
		return totalEntities;
	}

	public int totalLivingEntities() {
		return totalLivingEntities;
	}

	public int totalChunks() {
		return totalChunks;
	}

	@Override
	public boolean equals(Object value) {
		if (this == value) {
			return true;
		}

		if (!(value instanceof PlaceholderSnapshot)) {
			return false;
		}

		PlaceholderSnapshot other = (PlaceholderSnapshot) value;

		return currentTps == other.currentTps
			&& currentTickDurationMillis == other.currentTickDurationMillis
			&& Objects.equals(sparkTickDuration10Sec, other.sparkTickDuration10Sec)
			&& playerPingMillis == other.playerPingMillis
			&& uptimeMillis == other.uptimeMillis
			&& systemCpuPercent == other.systemCpuPercent
			&& processCpuPercent == other.processCpuPercent
			&& ramUsedBytes == other.ramUsedBytes
			&& ramMaxBytes == other.ramMaxBytes
			&& totalEntities == other.totalEntities
			&& totalLivingEntities == other.totalLivingEntities
			&& totalChunks == other.totalChunks;
	}

	@Override
	public int hashCode() {
		return Objects.hash(currentTps, currentTickDurationMillis, sparkTickDuration10Sec, playerPingMillis, uptimeMillis, systemCpuPercent, processCpuPercent, ramUsedBytes, ramMaxBytes, totalEntities, totalLivingEntities, totalChunks);
	}

	@Override
	public String toString() {
		return "PlaceholderSnapshot[" + "currentTps=" + currentTps + ", " + "currentTickDurationMillis=" + currentTickDurationMillis + ", " + "sparkTickDuration10Sec=" + sparkTickDuration10Sec + ", " + "playerPingMillis=" + playerPingMillis + ", " + "uptimeMillis=" + uptimeMillis + ", " + "systemCpuPercent=" + systemCpuPercent + ", " + "processCpuPercent=" + processCpuPercent + ", " + "ramUsedBytes=" + ramUsedBytes + ", " + "ramMaxBytes=" + ramMaxBytes + ", " + "totalEntities=" + totalEntities + ", " + "totalLivingEntities=" + totalLivingEntities + ", " + "totalChunks=" + totalChunks + "]";
	}

}
