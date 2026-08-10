package dev.belikhun.luna.tabbridge.mc.runtime;

import java.util.UUID;

public record TabBridgePacket(
	UUID playerId,
	String playerName,
	byte[] payload,
	long receivedAtEpochMillis
) {
	public TabBridgePacket {
		playerName = playerName == null ? "" : playerName;
		payload = payload == null ? new byte[0] : payload.clone();
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}
}
