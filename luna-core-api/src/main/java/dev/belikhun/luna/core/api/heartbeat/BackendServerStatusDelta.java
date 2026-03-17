package dev.belikhun.luna.core.api.heartbeat;

public record BackendServerStatusDelta(
	String serverName,
	boolean self,
	String serverDisplay,
	String serverAccentColor,
	Boolean online,
	Long lastHeartbeatEpochMillis,
	BackendHeartbeatStatsDelta stats
) {
	public static BackendServerStatusDelta diff(BackendServerStatus previous, BackendServerStatus current) {
		if (current == null) {
			throw new IllegalArgumentException("current cannot be null");
		}

		return new BackendServerStatusDelta(
			current.serverName(),
			false,
			changed(previous == null ? null : previous.serverDisplay(), current.serverDisplay()),
			changed(previous == null ? null : previous.serverAccentColor(), current.serverAccentColor()),
			changed(previous == null ? null : previous.online(), current.online()),
			changed(previous == null ? null : previous.lastHeartbeatEpochMillis(), current.lastHeartbeatEpochMillis()),
			BackendHeartbeatStatsDelta.diff(previous == null ? null : previous.stats(), current.stats())
		);
	}

	public BackendServerStatusDelta withSelf(boolean self) {
		return new BackendServerStatusDelta(serverName, self, serverDisplay, serverAccentColor, online, lastHeartbeatEpochMillis, stats);
	}

	public BackendServerStatus applyTo(BackendServerStatus previous) {
		BackendServerStatus base = previous == null
			? new BackendServerStatus(serverName, serverName, "", false, 0L, null)
			: previous;

		String resolvedName = (serverName == null || serverName.isBlank()) ? base.serverName() : serverName;
		String resolvedDisplay = serverDisplay != null ? serverDisplay : base.serverDisplay();
		if (resolvedDisplay == null || resolvedDisplay.isBlank()) {
			resolvedDisplay = resolvedName;
		}

		String resolvedAccent = serverAccentColor != null ? serverAccentColor : base.serverAccentColor();
		boolean resolvedOnline = online != null ? online : base.online();
		long resolvedLastHeartbeat = lastHeartbeatEpochMillis != null ? lastHeartbeatEpochMillis : base.lastHeartbeatEpochMillis();
		BackendHeartbeatStats resolvedStats = stats == null ? base.stats() : stats.applyTo(base.stats());

		return new BackendServerStatus(
			resolvedName,
			resolvedDisplay,
			resolvedAccent == null ? "" : resolvedAccent,
			resolvedOnline,
			resolvedLastHeartbeat,
			resolvedStats
		);
	}

	public boolean isEmpty() {
		return serverDisplay == null
			&& serverAccentColor == null
			&& online == null
			&& lastHeartbeatEpochMillis == null
			&& (stats == null || stats.isEmpty());
	}

	private static <T> T changed(T previous, T current) {
		if (previous == null) {
			return current;
		}
		return java.util.Objects.equals(previous, current) ? null : current;
	}
}
