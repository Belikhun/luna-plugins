package dev.belikhun.luna.auth.backend.mc.config;

import java.util.Map;
import java.util.Set;

public record AuthBackendConfig(
	boolean authFlowLogsEnabled,
	boolean modeSelectorGuiEnabled,
	boolean lobbyItemsEnabled,
	boolean teleportToSpawnOnConnect,
	Set<String> allowedCommands,
	PromptTemplate pendingPrompt,
	PromptTemplate loginPrompt,
	PromptTemplate registerPrompt,
	AuthenticatedPrompt authenticatedPrompt
) {
	public record PromptTemplate(String bossbar, String actionbar, String chat) {
	}

	public record AuthenticatedPrompt(String actionbar, String chat, Map<String, MethodFeedback> byMethod) {
	}

	public record MethodFeedback(String actionbar, String chat) {
	}
}
