package dev.belikhun.luna.legacy.auth;

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

	public static final class AuthState {
		private final boolean authenticated;
		private final PromptMode promptMode;

		public AuthState(boolean authenticated, PromptMode promptMode) {
			this.authenticated = authenticated;
			this.promptMode = promptMode;
		}

		public boolean authenticated() {
			return authenticated;
		}

		public PromptMode promptMode() {
			return promptMode;
		}

		public static AuthState pendingState() {
			return new AuthState(false, PromptMode.PENDING);
		}

		public static AuthState authenticatedState() {
			return new AuthState(true, PromptMode.PENDING);
		}

		public static AuthState unauthenticatedState(boolean needsRegister) {
			return new AuthState(false, needsRegister ? PromptMode.REGISTER : PromptMode.LOGIN);
		}

		/**
		 * The auth flow logs states by name, and this used to be a record.
		 *
		 * Downgrading it to a plain class dropped the generated toString, which turned
		 * every flow line into `AuthState@a87d8f0` - a log that names the transition
		 * but not what it transitioned to.
		 */
		@Override
		public String toString() {
			return "AuthState[authenticated=" + authenticated + ", promptMode=" + promptMode + "]";
		}
		}
}