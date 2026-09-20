package dev.belikhun.luna.vault.api.rpc;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;

/**
 * The version of the request and response layout on the wire.
 *
 * Every frame starts with it, so a proxy and a backend built from different
 * trees refuse each other with a clear reason instead of misreading a field
 * and failing somewhere downstream. Bump it whenever a field is added, removed
 * or reordered in {@link VaultRpcRequest} or {@link VaultRpcResponse}.
 */
public final class VaultRpcProtocol {
	/** Version 2: idempotent ledger, versioned snapshots, no session counters. */
	public static final int VERSION = 2;

	private VaultRpcProtocol() {
	}

	/**
	 * Read the leading version and say whether this build can decode the rest.
	 *
	 * @param reader positioned at the start of a frame
	 * @return the version found, which equals {@link #VERSION} when compatible
	 */
	public static int readVersion(PluginMessageReader reader) {
		return reader.readShort();
	}

	public static boolean compatible(int version) {
		return version == VERSION;
	}
}
