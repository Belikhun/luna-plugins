package dev.belikhun.luna.tabbridge.fabric.runtime;

import java.util.UUID;

public record FabricTabBridgePacket(
	UUID playerId,
	String playerName,
	byte[] payload,
	long receivedAtEpochMillis
) {
	public FabricTabBridgePacket {
		playerName = playerName == null ? "" : playerName;
		payload = payload == null ? new byte[0] : payload.clone();
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}
}
