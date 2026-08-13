package dev.belikhun.luna.legacy.config;

import dev.belikhun.luna.legacy.string.Strings;

/**
 * The slice of a backend's config.yml that every mod-loader platform reads the same
 * way. The loaders differ in where the file lives, never in what it says, so the
 * shape and its defaults live here rather than once per platform.
 *
 * There is deliberately no AMQP block: a backend's messaging settings come from the
 * proxy over the heartbeat, the way Paper has always taken them.
 *
 * A record in the modern api, so the normalisation its compact constructor did is
 * done in the constructor here - and it has to stay there rather than move to the
 * caller, because `defaults()` and the loader both depend on it.
 */
public final class BackendCoreRuntimeConfig {
	private final boolean ansiLoggingEnabled;
	private final String loggingLevel;
	private final boolean pluginMessagingLoggingEnabled;
	private final HeartbeatConfig heartbeatConfig;

	public BackendCoreRuntimeConfig(
		boolean ansiLoggingEnabled,
		String loggingLevel,
		boolean pluginMessagingLoggingEnabled,
		HeartbeatConfig heartbeatConfig
	) {
		this.ansiLoggingEnabled = ansiLoggingEnabled;
		this.loggingLevel = Strings.isBlank(loggingLevel) ? "INFO" : loggingLevel.trim();
		this.pluginMessagingLoggingEnabled = pluginMessagingLoggingEnabled;
		this.heartbeatConfig = heartbeatConfig == null ? HeartbeatConfig.defaults() : heartbeatConfig.sanitize();
	}

	public static BackendCoreRuntimeConfig defaults() {
		return new BackendCoreRuntimeConfig(true, "INFO", false, HeartbeatConfig.defaults());
	}

	public boolean ansiLoggingEnabled() {
		return ansiLoggingEnabled;
	}

	public String loggingLevel() {
		return loggingLevel;
	}

	public boolean pluginMessagingLoggingEnabled() {
		return pluginMessagingLoggingEnabled;
	}

	public HeartbeatConfig heartbeatConfig() {
		return heartbeatConfig;
	}

	public boolean debugLoggingEnabled() {
		return "DEBUG".equalsIgnoreCase(loggingLevel) || "TRACE".equalsIgnoreCase(loggingLevel);
	}

	/** Where the backend reports to, and how often. */
	public static final class HeartbeatConfig {
		private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:32452/api/heartbeat";

		private final boolean enabled;
		private final String endpoint;
		private final String serverName;
		private final int intervalSeconds;
		private final int connectTimeoutMillis;
		private final int readTimeoutMillis;
		private final boolean streamEnabled;
		private final boolean transportLoggingEnabled;

		public HeartbeatConfig(
			boolean enabled,
			String endpoint,
			String serverName,
			int intervalSeconds,
			int connectTimeoutMillis,
			int readTimeoutMillis,
			boolean streamEnabled,
			boolean transportLoggingEnabled
		) {
			this.enabled = enabled;
			this.endpoint = endpoint;
			this.serverName = serverName;
			this.intervalSeconds = intervalSeconds;
			this.connectTimeoutMillis = connectTimeoutMillis;
			this.readTimeoutMillis = readTimeoutMillis;
			this.streamEnabled = streamEnabled;
			this.transportLoggingEnabled = transportLoggingEnabled;
		}

		public static HeartbeatConfig defaults() {
			return new HeartbeatConfig(true, DEFAULT_ENDPOINT, "", 5, 3000, 3000, true, false);
		}

		/** Floors every timing value, so a hand-edited config cannot busy-loop. */
		public HeartbeatConfig sanitize() {
			return new HeartbeatConfig(
				enabled,
				Strings.isBlank(endpoint) ? DEFAULT_ENDPOINT : endpoint.trim(),
				Strings.trimmed(serverName),
				Math.max(1, intervalSeconds),
				Math.max(500, connectTimeoutMillis),
				Math.max(500, readTimeoutMillis),
				streamEnabled,
				transportLoggingEnabled
			);
		}

		public boolean enabled() {
			return enabled;
		}

		public String endpoint() {
			return endpoint;
		}

		public String serverName() {
			return serverName;
		}

		public int intervalSeconds() {
			return intervalSeconds;
		}

		public int connectTimeoutMillis() {
			return connectTimeoutMillis;
		}

		public int readTimeoutMillis() {
			return readTimeoutMillis;
		}

		public boolean streamEnabled() {
			return streamEnabled;
		}

		public boolean transportLoggingEnabled() {
			return transportLoggingEnabled;
		}
	}
}
