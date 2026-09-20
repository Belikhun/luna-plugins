package dev.belikhun.luna.vault.api;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.UUID;

/**
 * What the proxy last said about one player: balance and rank.
 *
 * The version orders snapshots of the same player. The proxy stamps every one
 * it emits with a value that only grows, so a backend receiving two out of
 * order keeps the newer one, and a refresh that crossed a reply on the wire
 * cannot roll a balance back to what it was a moment earlier.
 */
public record VaultPlayerSnapshot(
	UUID playerId,
	String playerName,
	long balanceMinor,
	int rank,
	long version
) {
	public VaultPlayerSnapshot(UUID playerId, String playerName, long balanceMinor, int rank) {
		this(playerId, playerName, balanceMinor, rank, 0L);
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeBoolean(playerId != null);

		if (playerId != null) {
			writer.writeUuid(playerId);
		}

		writer.writeUtf(playerName == null ? "" : playerName);
		writer.writeLong(balanceMinor);
		writer.writeInt(rank);
		writer.writeLong(version);
	}

	public static VaultPlayerSnapshot readFrom(PluginMessageReader reader) {
		UUID playerId = reader.readBoolean() ? reader.readUuid() : null;
		String playerName = reader.readUtf();
		long balanceMinor = reader.readLong();
		int rank = reader.readInt();
		long version = reader.readLong();

		return new VaultPlayerSnapshot(playerId, playerName == null ? "" : playerName, balanceMinor, rank, version);
	}

	public static VaultPlayerSnapshot empty(UUID playerId, String playerName) {
		return new VaultPlayerSnapshot(playerId, playerName == null ? "" : playerName, 0L, 0, 0L);
	}

	/** The same snapshot carrying a different version stamp. */
	public VaultPlayerSnapshot withVersion(long newVersion) {
		return new VaultPlayerSnapshot(playerId, playerName, balanceMinor, rank, newVersion);
	}

	/** Whether this snapshot is at least as recent as the other one. */
	public boolean supersedes(VaultPlayerSnapshot other) {
		if (other == null) {
			return true;
		}

		return version >= other.version();
	}
}
