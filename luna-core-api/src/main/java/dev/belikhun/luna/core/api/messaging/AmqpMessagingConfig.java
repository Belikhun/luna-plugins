package dev.belikhun.luna.core.api.messaging;

import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;

import java.util.Locale;

public record AmqpMessagingConfig(
	boolean enabled,
	String uri,
	String exchange,
	String proxyQueue,
	String backendQueuePrefix,
	String localServerName,
	int connectionTimeoutMillis,
	int requestedHeartbeatSeconds
) {
	public static AmqpMessagingConfig disabled() {
		return new AmqpMessagingConfig(false, "", "", "", "", "", 5000, 15);
	}

	public boolean isConfigured() {
		return enabled
			&& !uri().isBlank()
			&& !exchange().isBlank()
			&& !proxyQueue().isBlank()
			&& !backendQueuePrefix().isBlank();
	}

	public String backendQueue(String serverName) {
		return backendQueuePrefix() + normalizeServerName(serverName);
	}

	public String effectiveLocalServerName(String fallbackServerName) {
		String local = localServerName == null ? "" : localServerName.trim();
		if (!local.isBlank()) {
			return local;
		}
		return fallbackServerName == null ? "" : fallbackServerName.trim();
	}

	public BackendMetadata effectiveLocalBackendMetadata(BackendMetadata fallbackMetadata) {
		BackendMetadata sanitizedFallback = fallbackMetadata == null ? null : fallbackMetadata.sanitize();
		String local = localServerName == null ? "" : localServerName.trim();
		if (!local.isBlank()) {
			String displayName = sanitizedFallback == null ? local : sanitizedFallback.displayName();
			String accentColor = sanitizedFallback == null ? "" : sanitizedFallback.accentColor();
			return new BackendMetadata(local, displayName, accentColor).sanitize();
		}

		if (sanitizedFallback != null) {
			return sanitizedFallback;
		}

		return new BackendMetadata("", "", "").sanitize();
	}

	public String normalizeServerName(String serverName) {
		if (serverName == null) {
			return "";
		}

		String normalized = serverName.trim().toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(normalized.length());
		for (char value : normalized.toCharArray()) {
			boolean safe = (value >= 'a' && value <= 'z')
				|| (value >= '0' && value <= '9')
				|| value == '-'
				|| value == '_'
				|| value == '.';
			out.append(safe ? value : '-');
		}
		return out.toString();
	}

	public String maskedUri() {
		String value = uri();
		if (value == null || value.isBlank()) {
			return "<empty>";
		}

		int schemeIndex = value.indexOf("://");
		int atIndex = value.indexOf('@');
		if (schemeIndex < 0 || atIndex < 0 || atIndex <= schemeIndex + 3) {
			return value;
		}

		int credentialStart = schemeIndex + 3;
		int colonIndex = value.indexOf(':', credentialStart);
		if (colonIndex < 0 || colonIndex > atIndex) {
			return value.substring(0, credentialStart) + "***@" + value.substring(atIndex + 1);
		}

		return value.substring(0, colonIndex + 1) + "***@" + value.substring(atIndex + 1);
	}

	public AmqpMessagingConfig sanitize() {
		return new AmqpMessagingConfig(
			enabled,
			uri == null ? "" : uri.trim(),
			exchange == null ? "" : exchange.trim(),
			proxyQueue == null ? "" : proxyQueue.trim(),
			backendQueuePrefix == null ? "" : backendQueuePrefix.trim(),
			localServerName == null ? "" : localServerName.trim(),
			Math.max(500, connectionTimeoutMillis),
			Math.max(5, requestedHeartbeatSeconds)
		);
	}
}
