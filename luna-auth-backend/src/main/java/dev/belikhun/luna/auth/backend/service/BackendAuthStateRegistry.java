package dev.belikhun.luna.auth.backend.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendAuthStateRegistry {
	private final Map<UUID, Boolean> states;

	public BackendAuthStateRegistry() {
		this.states = new ConcurrentHashMap<>();
	}

	public void markUnauthenticated(UUID playerUuid) {
		states.put(playerUuid, false);
	}

	public void markAuthenticated(UUID playerUuid) {
		states.put(playerUuid, true);
	}

	public boolean isAuthenticated(UUID playerUuid) {
		return states.getOrDefault(playerUuid, false);
	}

	public void clear(UUID playerUuid) {
		states.remove(playerUuid);
	}
}
