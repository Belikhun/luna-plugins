package dev.belikhun.luna.auth.backend.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendAuthStateRegistry {
	private final Map<UUID, AuthState> states;

	public BackendAuthStateRegistry() {
		this.states = new ConcurrentHashMap<>();
	}

	public void markUnauthenticated(UUID playerUuid) {
		states.put(playerUuid, AuthState.pendingState());
	}

	public void markUnauthenticated(UUID playerUuid, boolean needsRegister) {
		states.put(playerUuid, AuthState.unauthenticatedState(needsRegister));
	}

	public void markAuthenticated(UUID playerUuid) {
		states.put(playerUuid, AuthState.authenticatedState());
	}

	public boolean isAuthenticated(UUID playerUuid) {
		return states.getOrDefault(playerUuid, AuthState.pendingState()).authenticated();
	}

	public AuthState state(UUID playerUuid) {
		return states.getOrDefault(playerUuid, AuthState.pendingState());
	}

	public boolean hasState(UUID playerUuid) {
		return states.containsKey(playerUuid);
	}

	public void clear(UUID playerUuid) {
		states.remove(playerUuid);
	}

	public enum PromptMode {
		PENDING,
		LOGIN,
		REGISTER
	}

	public record AuthState(boolean authenticated, PromptMode promptMode) {
		public static AuthState pendingState() {
			return new AuthState(false, PromptMode.PENDING);
		}

		public static AuthState authenticatedState() {
			return new AuthState(true, PromptMode.PENDING);
		}

		public static AuthState unauthenticatedState(boolean needsRegister) {
			return new AuthState(false, needsRegister ? PromptMode.REGISTER : PromptMode.LOGIN);
		}
	}
}
