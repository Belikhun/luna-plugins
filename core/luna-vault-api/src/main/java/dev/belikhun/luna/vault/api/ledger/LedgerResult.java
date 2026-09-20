package dev.belikhun.luna.vault.api.ledger;

import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;

import java.util.List;

/**
 * What one ledger operation came to, plus the state it left behind.
 *
 * {@code snapshots} are the post-commit balances of every account the
 * operation touched, already stamped with a version. They are what the proxy
 * puts in the reply and pushes to the backends holding those players, so a
 * caller never has to read the accounts again to learn what it just did.
 * {@code replayed} marks an outcome that was recorded by an earlier delivery
 * of the same operation id rather than applied now.
 */
public record LedgerResult(
	VaultOperationResult result,
	List<VaultPlayerSnapshot> snapshots,
	boolean replayed
) {
	public LedgerResult {
		snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
	}

	public static LedgerResult failed(VaultFailureReason reason, String message, long balanceMinor) {
		return new LedgerResult(VaultOperationResult.failed(reason, message, balanceMinor), List.of(), false);
	}

	public boolean success() {
		return result.success();
	}
}
