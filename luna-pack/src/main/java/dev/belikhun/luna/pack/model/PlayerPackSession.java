package dev.belikhun.luna.pack.model;

import com.velocitypowered.api.proxy.player.ResourcePackInfo;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerPackSession {
	private final UUID playerId;
	private final Map<String, ResourcePackInfo> loadedByName;
	private final Map<UUID, String> pendingByPackId;
	private String lastKnownServer;
	private String previousServer;
	private String lastFailure;
	private boolean debugEnabled;
	private long transitionSequence;
	private long handledFailureTransition;

	public PlayerPackSession(UUID playerId) {
		this.playerId = playerId;
		this.loadedByName = new ConcurrentHashMap<>();
		this.pendingByPackId = new ConcurrentHashMap<>();
	}

	public UUID playerId() {
		return playerId;
	}

	public String lastKnownServer() {
		return lastKnownServer;
	}

	public void lastKnownServer(String server) {
		this.lastKnownServer = server;
	}

	public String previousServer() {
		return previousServer;
	}

	public void previousServer(String server) {
		this.previousServer = server;
	}

	public String lastFailure() {
		return lastFailure;
	}

	public void lastFailure(String value) {
		this.lastFailure = value;
	}

	public boolean debugEnabled() {
		return debugEnabled;
	}

	public void debugEnabled(boolean value) {
		this.debugEnabled = value;
	}

	public long beginTransition() {
		transitionSequence += 1L;
		return transitionSequence;
	}

	public long transitionSequence() {
		return transitionSequence;
	}

	public boolean isFailureHandledForCurrentTransition() {
		return handledFailureTransition == transitionSequence;
	}

	public void markFailureHandledForCurrentTransition() {
		handledFailureTransition = transitionSequence;
	}

	public void addLoaded(String normalizedPackName, ResourcePackInfo info) {
		loadedByName.put(normalizedPackName, info);
	}

	public void removeLoaded(String normalizedPackName) {
		loadedByName.remove(normalizedPackName);
	}

	public boolean isLoaded(String normalizedPackName) {
		return loadedByName.containsKey(normalizedPackName);
	}

	public Map<String, ResourcePackInfo> loadedByName() {
		return loadedByName;
	}

	public Collection<ResourcePackInfo> loadedInfos() {
		return loadedByName.values();
	}

	public void addPending(UUID packId, String normalizedPackName) {
		pendingByPackId.put(packId, normalizedPackName);
	}

	public String removePending(UUID packId) {
		return pendingByPackId.remove(packId);
	}

	public Map<UUID, String> pendingByPackId() {
		return pendingByPackId;
	}
}
