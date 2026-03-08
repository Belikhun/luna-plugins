package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.pack.model.PlayerPackSession;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerPackSessionStore {
	private final ConcurrentMap<UUID, PlayerPackSession> sessions = new ConcurrentHashMap<>();

	public PlayerPackSession init(UUID playerId) {
		return sessions.computeIfAbsent(playerId, PlayerPackSession::new);
	}

	public PlayerPackSession getOrCreate(UUID playerId) {
		return sessions.computeIfAbsent(playerId, PlayerPackSession::new);
	}

	public PlayerPackSession get(UUID playerId) {
		return sessions.get(playerId);
	}

	public void remove(UUID playerId) {
		sessions.remove(playerId);
	}
}
