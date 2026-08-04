package dev.belikhun.luna.core.api.heartbeat;

/**
 * One registry row as it travels on the wire: a complete backend status plus the
 * registry revision it was written at.
 *
 * Rows are always complete. The protocol deliberately carries no field-level
 * diffs — a diff that a consumer misses is lost forever, which is exactly how
 * rarely-changing fields (online, whitelist, display name) used to go stale on
 * backends and never heal.
 */
public record BackendStatusRow(
	BackendServerStatus status,
	long revision,
	boolean self
) {
	public BackendStatusRow(BackendServerStatus status, long revision) {
		this(status, revision, false);
	}

	public BackendStatusRow withSelf(boolean self) {
		return new BackendStatusRow(status, revision, self);
	}

	public String serverName() {
		return status == null ? "" : status.serverName();
	}
}
