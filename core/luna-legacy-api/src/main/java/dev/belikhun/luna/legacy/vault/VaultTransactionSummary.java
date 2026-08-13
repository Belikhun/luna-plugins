package dev.belikhun.luna.legacy.vault;

/**
 * Lifetime totals for one player's transactions.
 *
 * A page of history answers "what happened recently"; this answers "how much has
 * ever moved", which is the question a console overview asks. Received and sent
 * are kept apart rather than netted, because a balance of zero reached by never
 * earning anything and one reached by spending everything are different states.
 */
public final class VaultTransactionSummary {
	private final int transactionCount;
	private final long receivedMinor;
	private final long sentMinor;
	private final long firstAtEpochMillis;
	private final long lastAtEpochMillis;

	public VaultTransactionSummary(int transactionCount, long receivedMinor, long sentMinor, long firstAtEpochMillis, long lastAtEpochMillis) {
		this.transactionCount = transactionCount;
		this.receivedMinor = receivedMinor;
		this.sentMinor = sentMinor;
		this.firstAtEpochMillis = firstAtEpochMillis;
		this.lastAtEpochMillis = lastAtEpochMillis;
	}

	public int transactionCount() {
		return transactionCount;
	}

	public long receivedMinor() {
		return receivedMinor;
	}

	public long sentMinor() {
		return sentMinor;
	}

	public long firstAtEpochMillis() {
		return firstAtEpochMillis;
	}

	public long lastAtEpochMillis() {
		return lastAtEpochMillis;
	}

	public static VaultTransactionSummary empty() {
		return new VaultTransactionSummary(0, 0L, 0L, 0L, 0L);
	}

	/** Received minus sent — the net effect of every recorded transaction. */
	public long netMinor() {
		return receivedMinor - sentMinor;
	}
}
