package dev.belikhun.luna.legacy.heartbeat;

import java.util.Collections;
import java.util.List;

/**
 * A decoded registry payload: either a full mirror or the rows that changed since
 * the caller's cursor.
 *
 * Top-level here, where the modern api nests it inside `HeartbeatFormCodec`. A nested
 * record costs nothing; a nested 80-line class buries the codec it sits in, and
 * nothing outside this module refers to it by its qualified name yet.
 */
public final class HeartbeatSnapshotPayload {
	private final int protocol;
	private final String epoch;
	private final long revision;
	private final boolean fullSync;
	private final List<BackendStatusRow> rows;
	private final BackendMetadata currentBackendMetadata;

	public HeartbeatSnapshotPayload(
		int protocol,
		String epoch,
		long revision,
		boolean fullSync,
		List<BackendStatusRow> rows,
		BackendMetadata currentBackendMetadata
	) {
		this.protocol = protocol;
		this.epoch = epoch;
		this.revision = revision;
		this.fullSync = fullSync;
		this.rows = rows == null ? Collections.<BackendStatusRow>emptyList() : rows;
		this.currentBackendMetadata = currentBackendMetadata;
	}

	/** The sender's {@link HeartbeatFormCodec#PROTOCOL_VERSION}, 0 when it predates the field. */
	public int protocol() {
		return protocol;
	}

	/** The registry generation; a change means the caller's cursor is meaningless. */
	public String epoch() {
		return epoch;
	}

	public long revision() {
		return revision;
	}

	public boolean fullSync() {
		return fullSync;
	}

	public List<BackendStatusRow> rows() {
		return rows;
	}

	public BackendMetadata currentBackendMetadata() {
		return currentBackendMetadata;
	}

	@Override
	public String toString() {
		return "HeartbeatSnapshotPayload[protocol=" + protocol
			+ ", epoch=" + epoch
			+ ", revision=" + revision
			+ ", fullSync=" + fullSync
			+ ", rows=" + rows.size() + "]";
	}
}
