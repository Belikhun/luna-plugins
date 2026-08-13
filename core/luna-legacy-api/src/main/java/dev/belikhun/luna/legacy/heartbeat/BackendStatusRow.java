package dev.belikhun.luna.legacy.heartbeat;

import java.util.Objects;

/**
 * One registry row as it travels on the wire: a complete backend status plus the
 * registry revision it was written at.
 *
 * Rows are always complete. The protocol deliberately carries no field-level diffs -
 * a diff that a consumer misses is lost forever, which is exactly how rarely-changing
 * fields (online, whitelist, display name) used to go stale on backends and never
 * heal.
 */
public final class BackendStatusRow {
	private final BackendServerStatus status;
	private final long revision;
	private final boolean self;

	public BackendStatusRow(BackendServerStatus status, long revision, boolean self) {
		this.status = status;
		this.revision = revision;
		this.self = self;
	}

	public BackendStatusRow(BackendServerStatus status, long revision) {
		this(status, revision, false);
	}

	public BackendServerStatus status() {
		return status;
	}

	public long revision() {
		return revision;
	}

	public boolean self() {
		return self;
	}

	public BackendStatusRow withSelf(boolean self) {
		return new BackendStatusRow(status, revision, self);
	}

	public String serverName() {
		return status == null ? "" : status.serverName();
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof BackendStatusRow)) {
			return false;
		}

		BackendStatusRow that = (BackendStatusRow) other;

		return revision == that.revision && self == that.self && Objects.equals(status, that.status);
	}

	@Override
	public int hashCode() {
		return Objects.hash(status, revision, self);
	}

	@Override
	public String toString() {
		return "BackendStatusRow[status=" + status + ", revision=" + revision + ", self=" + self + "]";
	}
}
