package dev.belikhun.luna.legacy.vault;

import java.util.UUID;

public final class VaultLeaderboardEntry {
	private final int rank;
	private final UUID playerId;
	private final String playerName;
	private final long balanceMinor;

	public VaultLeaderboardEntry(int rank, UUID playerId, String playerName, long balanceMinor) {
		this.rank = rank;
		this.playerId = playerId;
		this.playerName = playerName;
		this.balanceMinor = balanceMinor;
	}

	public int rank() {
		return rank;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public long balanceMinor() {
		return balanceMinor;
	}

}
