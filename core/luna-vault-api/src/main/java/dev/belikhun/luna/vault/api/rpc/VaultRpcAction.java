package dev.belikhun.luna.vault.api.rpc;

/**
 * What a backend can ask the proxy.
 *
 * The mutating four carry an operation id and are safe to send twice; the
 * proxy answers a repeat with the outcome it recorded the first time.
 * {@link #LOOKUP} asks for exactly that recorded outcome, which is how a
 * backend settles a request whose reply never arrived.
 */
public enum VaultRpcAction {
	SNAPSHOT,
	BALANCE,
	DEPOSIT,
	WITHDRAW,
	TRANSFER,
	SET_BALANCE,
	HISTORY,
	LEADERBOARD,
	LOOKUP;

	public boolean mutating() {
		return this == DEPOSIT
			|| this == WITHDRAW
			|| this == TRANSFER
			|| this == SET_BALANCE;
	}
}
