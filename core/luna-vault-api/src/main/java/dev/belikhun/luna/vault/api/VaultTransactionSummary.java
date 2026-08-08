package dev.belikhun.luna.vault.api;

/**
 * Lifetime totals for one player's transactions.
 *
 * A page of history answers "what happened recently"; this answers "how much has
 * ever moved", which is the question a console overview asks. Received and sent
 * are kept apart rather than netted, because a balance of zero reached by never
 * earning anything and one reached by spending everything are different states.
 */
public record VaultTransactionSummary(
	int transactionCount,
	long receivedMinor,
	long sentMinor,
	long firstAtEpochMillis,
	long lastAtEpochMillis
) {
	public static VaultTransactionSummary empty() {
		return new VaultTransactionSummary(0, 0L, 0L, 0L, 0L);
	}

	/** Received minus sent — the net effect of every recorded transaction. */
	public long netMinor() {
		return receivedMinor - sentMinor;
	}
}
