package dev.belikhun.luna.vault.api;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.UUID;

public record VaultPlayerSnapshot(
	UUID playerId,
	String playerName,
	long balanceMinor,
	int rank
) {
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
