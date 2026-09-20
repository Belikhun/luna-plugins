package dev.belikhun.luna.vault.api.ledger;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of reconciling accounts against the ledger.
 *
 * An account agrees with the ledger when its balance equals the running
 * balance on the newest row that names it and recorded one. An account with
 * no such row (imported, or untouched since the running-balance column
 * appeared) has nothing to disagree with and is counted, not flagged.
 */
public record LedgerAudit(
	int accountsChecked,
	int accountsWithoutHistory,
	long totalBalanceMinor,
	List<Mismatch> mismatches
) {
	public LedgerAudit {
		mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
	}

	public boolean clean() {
		return mismatches.isEmpty();
	}

	/** One account whose balance is not what its last ledger row says it should be. */
	public record Mismatch(
		UUID playerId,
		String playerName,
		long balanceMinor,
		long expectedMinor,
		String lastTransactionId,
		long lastCompletedAt
	) {
		public long differenceMinor() {
			return balanceMinor - expectedMinor;
		}
	}
}
