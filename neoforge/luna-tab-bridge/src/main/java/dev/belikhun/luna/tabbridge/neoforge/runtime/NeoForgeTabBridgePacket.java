package dev.belikhun.luna.tabbridge.neoforge.runtime;

import java.util.UUID;

public record NeoForgeTabBridgePacket(
	UUID playerId,
	String playerName,
	byte[] payload,
	long receivedAtEpochMillis
) {
	public NeoForgeTabBridgePacket {
		playerName = playerName == null ? "" : playerName;
		payload = payload == null ? new byte[0] : payload.clone();
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}
}
