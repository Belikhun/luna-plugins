package dev.belikhun.luna.legacy.heartbeat;

import java.util.Objects;

/** A backend as the registry currently sees it. */
public final class BackendServerStatus {
	private final String serverName;
	private final String serverDisplay;
	private final String serverAccentColor;
	private final boolean online;
	private final long lastHeartbeatEpochMillis;
	private final BackendHeartbeatStats stats;

	public BackendServerStatus(
		String serverName,
		String serverDisplay,
		String serverAccentColor,
		boolean online,
		long lastHeartbeatEpochMillis,
		BackendHeartbeatStats stats
	) {
		this.serverName = serverName;
		this.serverDisplay = serverDisplay;
		this.serverAccentColor = serverAccentColor;
		this.online = online;
		this.lastHeartbeatEpochMillis = lastHeartbeatEpochMillis;
		this.stats = stats;
	}

	public String serverName() {
		return serverName;
	}

	public String serverDisplay() {
		return serverDisplay;
	}

	public String serverAccentColor() {
		return serverAccentColor;
	}

	public boolean online() {
		return online;
	}

	public long lastHeartbeatEpochMillis() {
		return lastHeartbeatEpochMillis;
	}

	public BackendHeartbeatStats stats() {
		return stats;
	}

	public BackendMetadata metadata() {
		return new BackendMetadata(serverName, serverDisplay, serverAccentColor).sanitize();
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof BackendServerStatus)) {
			return false;
		}

		BackendServerStatus that = (BackendServerStatus) other;

		return online == that.online
			&& lastHeartbeatEpochMillis == that.lastHeartbeatEpochMillis
			&& Objects.equals(serverName, that.serverName)
			&& Objects.equals(serverDisplay, that.serverDisplay)
			&& Objects.equals(serverAccentColor, that.serverAccentColor)
			&& Objects.equals(stats, that.stats);
	}

	@Override
	public int hashCode() {
		return Objects.hash(serverName, serverDisplay, serverAccentColor, online, lastHeartbeatEpochMillis, stats);
	}

	@Override
	public String toString() {
		return "BackendServerStatus[serverName=" + serverName
			+ ", serverDisplay=" + serverDisplay
			+ ", online=" + online + "]";
	}
}
