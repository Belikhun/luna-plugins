package dev.belikhun.luna.vault.api;

/**
 * Why an operation did not go through.
 *
 * The first group is the ledger's own verdict and is final: retrying the same
 * request changes nothing. The second group is the transport's, and says
 * nothing about the money: a request that timed out may or may not have been
 * applied, which is what {@code operationId} and the replay lookup exist for.
 */
public enum VaultFailureReason {
	NONE,

	// ledger verdicts
	DATABASE_DISABLED,
	INVALID_AMOUNT,
	PLAYER_NOT_FOUND,
	TARGET_OFFLINE,
	INSUFFICIENT_FUNDS,
	SELF_TRANSFER,

	/** Kept for wire compatibility with older builds; the ledger no longer issues it. */
	@Deprecated
	STALE_SESSION,

	// transport verdicts
	TRANSPORT_ERROR,
	TIMEOUT,

	/** No online player could carry the request to the proxy. */
	NO_ROUTE,

	/** The other side speaks a different RPC protocol version. */
	PROTOCOL_MISMATCH,

	/** The economy is shutting down or not wired up on this server. */
	UNAVAILABLE,

	/** A lookup found no ledger row for the operation id: it was never applied. */
	NOT_RECORDED,

	INTERNAL_ERROR;

	/** Whether the same request, sent again, could end differently. */
	public boolean retryable() {
		return this == TRANSPORT_ERROR
			|| this == TIMEOUT
			|| this == NO_ROUTE
			|| this == UNAVAILABLE;
	}
}
