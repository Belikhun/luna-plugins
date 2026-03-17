package dev.belikhun.luna.core.api.heartbeat;

public record BackendHeartbeatStatsDelta(
	String software,
	String version,
	Integer serverPort,
	Long uptimeMillis,
	Double tps,
	Integer onlinePlayers,
	Integer maxPlayers,
	String motd,
	Boolean whitelistEnabled,
	Double systemCpuUsagePercent,
	Double processCpuUsagePercent,
	Long ramUsedBytes,
	Long ramFreeBytes,
	Long ramMaxBytes,
	Long heartbeatLatencyMillis
) {
	public static BackendHeartbeatStatsDelta diff(BackendHeartbeatStats previous, BackendHeartbeatStats current) {
		if (current == null) {
			return new BackendHeartbeatStatsDelta(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
		}

		return new BackendHeartbeatStatsDelta(
			changed(previous == null ? null : previous.software(), current.software()),
			changed(previous == null ? null : previous.version(), current.version()),
			changed(previous == null ? null : previous.serverPort(), current.serverPort()),
			changed(previous == null ? null : previous.uptimeMillis(), current.uptimeMillis()),
			changed(previous == null ? null : previous.tps(), current.tps()),
			changed(previous == null ? null : previous.onlinePlayers(), current.onlinePlayers()),
			changed(previous == null ? null : previous.maxPlayers(), current.maxPlayers()),
			changed(previous == null ? null : previous.motd(), current.motd()),
			changed(previous == null ? null : previous.whitelistEnabled(), current.whitelistEnabled()),
			changed(previous == null ? null : previous.systemCpuUsagePercent(), current.systemCpuUsagePercent()),
			changed(previous == null ? null : previous.processCpuUsagePercent(), current.processCpuUsagePercent()),
			changed(previous == null ? null : previous.ramUsedBytes(), current.ramUsedBytes()),
			changed(previous == null ? null : previous.ramFreeBytes(), current.ramFreeBytes()),
			changed(previous == null ? null : previous.ramMaxBytes(), current.ramMaxBytes()),
			changed(previous == null ? null : previous.heartbeatLatencyMillis(), current.heartbeatLatencyMillis())
		);
	}

	public BackendHeartbeatStats applyTo(BackendHeartbeatStats previous) {
		BackendHeartbeatStats base = previous == null
			? new BackendHeartbeatStats("unknown", "unknown", 0, 0L, 0D, 0, 0, "", false, 0D, 0D, 0L, 0L, 0L, 0L)
			: previous;

		return new BackendHeartbeatStats(
			software != null ? software : base.software(),
			version != null ? version : base.version(),
			serverPort != null ? serverPort : base.serverPort(),
			uptimeMillis != null ? uptimeMillis : base.uptimeMillis(),
			tps != null ? tps : base.tps(),
			onlinePlayers != null ? onlinePlayers : base.onlinePlayers(),
			maxPlayers != null ? maxPlayers : base.maxPlayers(),
			motd != null ? motd : base.motd(),
			whitelistEnabled != null ? whitelistEnabled : base.whitelistEnabled(),
			systemCpuUsagePercent != null ? systemCpuUsagePercent : base.systemCpuUsagePercent(),
			processCpuUsagePercent != null ? processCpuUsagePercent : base.processCpuUsagePercent(),
			ramUsedBytes != null ? ramUsedBytes : base.ramUsedBytes(),
			ramFreeBytes != null ? ramFreeBytes : base.ramFreeBytes(),
			ramMaxBytes != null ? ramMaxBytes : base.ramMaxBytes(),
			heartbeatLatencyMillis != null ? heartbeatLatencyMillis : base.heartbeatLatencyMillis()
		);
	}

	public boolean isEmpty() {
		return software == null
			&& version == null
			&& serverPort == null
			&& uptimeMillis == null
			&& tps == null
			&& onlinePlayers == null
			&& maxPlayers == null
			&& motd == null
			&& whitelistEnabled == null
			&& systemCpuUsagePercent == null
			&& processCpuUsagePercent == null
			&& ramUsedBytes == null
			&& ramFreeBytes == null
			&& ramMaxBytes == null
			&& heartbeatLatencyMillis == null;
	}

	private static <T> T changed(T previous, T current) {
		if (previous == null) {
			return current;
		}
		return java.util.Objects.equals(previous, current) ? null : current;
	}
}
