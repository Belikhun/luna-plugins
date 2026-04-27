package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;

public record NeoForgeCoreRuntimeConfig(
	boolean ansiLoggingEnabled,
	String loggingLevel,
	AmqpMessagingConfig amqpMessagingConfig,
	HeartbeatConfig heartbeatConfig
) {
	public NeoForgeCoreRuntimeConfig {
		loggingLevel = loggingLevel == null || loggingLevel.isBlank() ? "INFO" : loggingLevel.trim();
		amqpMessagingConfig = amqpMessagingConfig == null ? AmqpMessagingConfig.disabled() : amqpMessagingConfig.sanitize();
		heartbeatConfig = heartbeatConfig == null ? HeartbeatConfig.defaults() : heartbeatConfig.sanitize();
	}

	public boolean debugLoggingEnabled() {
		return "DEBUG".equalsIgnoreCase(loggingLevel) || "TRACE".equalsIgnoreCase(loggingLevel);
	}

	public record HeartbeatConfig(
		boolean enabled,
		String endpoint,
		String serverName,
		int intervalSeconds,
		int connectTimeoutMillis,
		int readTimeoutMillis,
		boolean transportLoggingEnabled
	) {
		public static HeartbeatConfig defaults() {
			return new HeartbeatConfig(
				true,
				"http://127.0.0.1:32452/api/heartbeat",
				"",
				5,
				3000,
				3000,
				false
			);
		}

		public HeartbeatConfig sanitize() {
			return new HeartbeatConfig(
				enabled,
				endpoint == null || endpoint.isBlank() ? "http://127.0.0.1:32452/api/heartbeat" : endpoint.trim(),
				serverName == null ? "" : serverName.trim(),
				Math.max(1, intervalSeconds),
				Math.max(500, connectTimeoutMillis),
				Math.max(500, readTimeoutMillis),
				transportLoggingEnabled
			);
		}
	}
}
