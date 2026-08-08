package dev.belikhun.luna.vault.api;

import java.util.UUID;

public record VaultLeaderboardEntry(
	int rank,
	UUID playerId,
	String playerName,
	long balanceMinor
) {
}
