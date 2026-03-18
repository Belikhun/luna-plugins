package dev.belikhun.luna.core.fabric.messaging;

import java.util.UUID;

public record FabricMessageSource(
	String serverName,
	UUID playerId,
	String playerName
) {
}
