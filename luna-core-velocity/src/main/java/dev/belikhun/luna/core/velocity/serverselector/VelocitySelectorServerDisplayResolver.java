package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;

import java.util.Locale;
import java.util.Map;

public final class VelocitySelectorServerDisplayResolver implements ServerDisplayResolver {
	private static final String DEFAULT_COLOR = "#F1FF68";

	private final VelocityServerSelectorConfig selectorConfig;
	private final BackendStatusView backendStatusView;

	public VelocitySelectorServerDisplayResolver(VelocityServerSelectorConfig selectorConfig, BackendStatusView backendStatusView) {
		this.selectorConfig = selectorConfig;
		this.backendStatusView = backendStatusView;
	}

	@Override
	public String serverDisplay(String serverName) {
		String normalized = normalize(serverName);
		if (normalized.isBlank()) {
			return "";
		}

		VelocityServerSelectorConfig.ServerDefinition selectorDefinition = selectorConfig.server(normalized);
		if (selectorDefinition != null && selectorDefinition.displayName() != null && !selectorDefinition.displayName().isBlank()) {
			return selectorDefinition.displayName();
		}

		BackendServerStatus status = status(normalized);
		if (status != null && status.serverDisplay() != null && !status.serverDisplay().isBlank()) {
			return status.serverDisplay();
		}

		return normalized;
	}

	@Override
	public String serverColor(String serverName) {
		String normalized = normalize(serverName);
		if (normalized.isBlank()) {
			return DEFAULT_COLOR;
		}

		VelocityServerSelectorConfig.ServerDefinition selectorDefinition = selectorConfig.server(normalized);
		if (selectorDefinition != null && selectorDefinition.accentColor() != null && !selectorDefinition.accentColor().isBlank()) {
			return selectorDefinition.accentColor();
		}

		BackendServerStatus status = status(normalized);
		if (status != null && status.serverAccentColor() != null && !status.serverAccentColor().isBlank()) {
			return status.serverAccentColor();
		}

		return DEFAULT_COLOR;
	}

	private BackendServerStatus status(String normalizedServerName) {
		Map<String, BackendServerStatus> snapshot = backendStatusView.snapshot();
		if (snapshot == null || snapshot.isEmpty()) {
			return null;
		}

		return snapshot.get(normalizedServerName);
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
