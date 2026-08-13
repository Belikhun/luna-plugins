package dev.belikhun.luna.legacy.vault;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.UUID;

public final class VaultPlayerSnapshot {
	private final UUID playerId;
	private final String playerName;
	private final long balanceMinor;
	private final int rank;

	public VaultPlayerSnapshot(UUID playerId, String playerName, long balanceMinor, int rank) {
		this.playerId = playerId;
		this.playerName = playerName;
		this.balanceMinor = balanceMinor;
		this.rank = rank;
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

	public int rank() {
		return rank;
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeBoolean(playerId != null);
		if (playerId != null) {
			writer.writeUuid(playerId);
		}
		writer.writeUtf(playerName == null ? "" : playerName);
		writer.writeLong(balanceMinor);
		writer.writeInt(rank);
	}

	public static VaultPlayerSnapshot readFrom(PluginMessageReader reader) {
		UUID playerId = reader.readBoolean() ? reader.readUuid() : null;
		String playerName = reader.readUtf();
		long balanceMinor = reader.readLong();
		int rank = reader.readInt();
		return new VaultPlayerSnapshot(playerId, playerName == null ? "" : playerName, balanceMinor, rank);
	}

	public static VaultPlayerSnapshot empty(UUID playerId, String playerName) {
		return new VaultPlayerSnapshot(playerId, playerName == null ? "" : playerName, 0L, 0);
	}
}
