package dev.belikhun.luna.core.api.config;

/**
 * The slice of a backend's config.yml that every mod-loader platform reads the
 * same way. NeoForge and Fabric differ in where the file lives, never in what it
 * says, so the shape and its defaults live here rather than once per platform.
 *
 * There is deliberately no AMQP block: a backend's messaging settings come from
 * the proxy over the heartbeat, the way Paper has always taken them.
 */
public record BackendCoreRuntimeConfig(
	boolean ansiLoggingEnabled,
	String loggingLevel,
	boolean pluginMessagingLoggingEnabled,
	HeartbeatConfig heartbeatConfig
) {
	public BackendCoreRuntimeConfig {
		loggingLevel = loggingLevel == null || loggingLevel.isBlank() ? "INFO" : loggingLevel.trim();
		heartbeatConfig = heartbeatConfig == null ? HeartbeatConfig.defaults() : heartbeatConfig.sanitize();
	}

	public static BackendCoreRuntimeConfig defaults() {
		return new BackendCoreRuntimeConfig(
			true,
			"INFO",
			false,
			HeartbeatConfig.defaults()
		);
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
		boolean streamEnabled,
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
				true,
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
				streamEnabled,
				transportLoggingEnabled
			);
		}
	}
}
