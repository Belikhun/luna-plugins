package dev.belikhun.luna.vault.api;

/**
 * What a ledger row records.
 *
 * Every row moves one amount between a sender and a receiver, and either side
 * may be "the system" (a null id). The kind says which of the four operations
 * wrote it, so a replayed operation can tell whose running balance to answer
 * with and a history view can label the row without guessing from its ids.
 */
public enum VaultTransactionKind {
	/** Money created into a player's account. */
	DEPOSIT,

	/** Money taken out of a player's account. */
	WITHDRAW,

	/** Money moved from one player to another. */
	TRANSFER,

	/** An administrator set a balance outright; the amount is the difference. */
	ADJUST;

	/**
	 * Parse a stored kind, defaulting rows written before the column existed.
	 *
	 * @param value the stored name, possibly null
	 * @return the kind, {@link #TRANSFER} for unknown or missing values
	 */
	public static VaultTransactionKind parse(String value) {
		if (value == null || value.isBlank()) {
			return TRANSFER;
		}

		try {
			return valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException unknown) {
			return TRANSFER;
		}
	}
}
