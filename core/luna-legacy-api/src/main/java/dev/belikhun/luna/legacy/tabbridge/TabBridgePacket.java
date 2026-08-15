package dev.belikhun.luna.legacy.tabbridge;

import java.util.UUID;

/**
 * The last thing TAB sent about one player, kept for diagnostics.
 *
 * The bridge answers packets as they arrive and does not need a history; this
 * exists so an operator asking "is TAB actually talking to this backend" can be
 * shown a timestamp rather than told to read a log.
 */
public final class TabBridgePacket {
	private final UUID playerId;
	private final String playerName;
	private final byte[] payload;
	private final long receivedAtEpochMillis;

	public TabBridgePacket(UUID playerId, String playerName, byte[] payload, long receivedAtEpochMillis) {
		this.playerId = playerId;
		this.playerName = playerName == null ? "" : playerName;
		this.payload = payload == null ? new byte[0] : payload.clone();
		this.receivedAtEpochMillis = receivedAtEpochMillis;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	/** A copy, so a caller inspecting a payload cannot rewrite what was received. */
	public byte[] payload() {
		return payload.clone();
	}

	public long receivedAtEpochMillis() {
		return receivedAtEpochMillis;
	}
}
