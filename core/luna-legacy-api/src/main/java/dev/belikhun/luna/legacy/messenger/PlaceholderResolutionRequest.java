package dev.belikhun.luna.legacy.messenger;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public final class PlaceholderResolutionRequest {
	private final UUID playerId;
	private final String playerName;
	private final String sourceServer;
	private final String content;
	private final Map<String, String> internalValues;

	public PlaceholderResolutionRequest(UUID playerId, String playerName, String sourceServer, String content, Map<String, String> internalValues) {
		Objects.requireNonNull(playerId, "playerId");
		playerName = playerName == null ? "" : playerName;
		sourceServer = sourceServer == null ? "" : sourceServer;
		content = content == null ? "" : content;
		internalValues = internalValues == null
			? Collections.<String, String>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<String, String>(internalValues));

		this.playerId = playerId;
		this.playerName = playerName;
		this.sourceServer = sourceServer;
		this.content = content;
		this.internalValues = internalValues;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public String sourceServer() {
		return sourceServer;
	}

	public String content() {
		return content;
	}

	public Map<String, String> internalValues() {
		return internalValues;
	}

}
