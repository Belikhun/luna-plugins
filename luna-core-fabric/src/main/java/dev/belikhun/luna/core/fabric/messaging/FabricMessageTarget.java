package dev.belikhun.luna.core.fabric.messaging;

import java.util.UUID;

public record FabricMessageTarget(
	String serverName,
	UUID playerId
) {
	public static FabricMessageTarget server(String serverName) {
		return new FabricMessageTarget(serverName, null);
	}

	public static FabricMessageTarget player(UUID playerId) {
		return new FabricMessageTarget("", playerId);
	}
}
