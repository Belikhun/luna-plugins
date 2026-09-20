package dev.belikhun.luna.vault.api.ledger;

import java.util.UUID;

/** One account row as read under lock inside a ledger transaction. */
record LedgerAccount(
	UUID playerId,
	String playerName,
	long balanceMinor
) {
}
