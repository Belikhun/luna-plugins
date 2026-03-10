package dev.belikhun.luna.core.api.messenger;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PlaceholderResolutionRequest(
	UUID playerId,
	String playerName,
	String sourceServer,
	String content,
	Map<String, String> internalValues
) {
	public PlaceholderResolutionRequest {
		Objects.requireNonNull(playerId, "playerId");
		playerName = playerName == null ? "" : playerName;
		sourceServer = sourceServer == null ? "" : sourceServer;
		content = content == null ? "" : content;
		internalValues = internalValues == null ? Map.of() : Map.copyOf(internalValues);
	}
}
