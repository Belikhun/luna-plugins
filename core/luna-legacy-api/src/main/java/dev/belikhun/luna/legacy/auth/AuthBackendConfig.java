package dev.belikhun.luna.legacy.auth;

import java.util.Map;
import java.util.Set;

public final class AuthBackendConfig {
	private final boolean authFlowLogsEnabled;
	private final boolean modeSelectorGuiEnabled;
	private final boolean lobbyItemsEnabled;
	private final boolean teleportToSpawnOnConnect;
	private final Set<String> allowedCommands;
	private final PromptTemplate pendingPrompt;
	private final PromptTemplate loginPrompt;
	private final PromptTemplate registerPrompt;
	private final AuthenticatedPrompt authenticatedPrompt;

	public AuthBackendConfig(boolean authFlowLogsEnabled, boolean modeSelectorGuiEnabled, boolean lobbyItemsEnabled, boolean teleportToSpawnOnConnect, Set<String> allowedCommands, PromptTemplate pendingPrompt, PromptTemplate loginPrompt, PromptTemplate registerPrompt, AuthenticatedPrompt authenticatedPrompt) {
		this.authFlowLogsEnabled = authFlowLogsEnabled;
		this.modeSelectorGuiEnabled = modeSelectorGuiEnabled;
		this.lobbyItemsEnabled = lobbyItemsEnabled;
		this.teleportToSpawnOnConnect = teleportToSpawnOnConnect;
		this.allowedCommands = allowedCommands;
		this.pendingPrompt = pendingPrompt;
		this.loginPrompt = loginPrompt;
		this.registerPrompt = registerPrompt;
		this.authenticatedPrompt = authenticatedPrompt;
	}

	public boolean authFlowLogsEnabled() {
		return authFlowLogsEnabled;
	}

	public boolean modeSelectorGuiEnabled() {
		return modeSelectorGuiEnabled;
	}

	public boolean lobbyItemsEnabled() {
		return lobbyItemsEnabled;
	}

	public boolean teleportToSpawnOnConnect() {
		return teleportToSpawnOnConnect;
	}

	public Set<String> allowedCommands() {
		return allowedCommands;
	}

	public PromptTemplate pendingPrompt() {
		return pendingPrompt;
	}

	public PromptTemplate loginPrompt() {
		return loginPrompt;
	}

	public PromptTemplate registerPrompt() {
		return registerPrompt;
	}

	public AuthenticatedPrompt authenticatedPrompt() {
		return authenticatedPrompt;
	}

	public static final class PromptTemplate {
		private final String bossbar;
		private final String actionbar;
		private final String chat;

		public PromptTemplate(String bossbar, String actionbar, String chat) {
			this.bossbar = bossbar;
			this.actionbar = actionbar;
			this.chat = chat;
		}

		public String bossbar() {
			return bossbar;
		}

		public String actionbar() {
			return actionbar;
		}

		public String chat() {
			return chat;
		}

		}

	public static final class AuthenticatedPrompt {
		private final String actionbar;
		private final String chat;
		private final Map<String, MethodFeedback> byMethod;

		public AuthenticatedPrompt(String actionbar, String chat, Map<String, MethodFeedback> byMethod) {
			this.actionbar = actionbar;
			this.chat = chat;
			this.byMethod = byMethod;
		}

		public String actionbar() {
			return actionbar;
		}

		public String chat() {
			return chat;
		}

		public Map<String, MethodFeedback> byMethod() {
			return byMethod;
		}

		}

	public static final class MethodFeedback {
		private final String actionbar;
		private final String chat;

		public MethodFeedback(String actionbar, String chat) {
			this.actionbar = actionbar;
			this.chat = chat;
		}

		public String actionbar() {
			return actionbar;
		}

		public String chat() {
			return chat;
		}

		}
}
