package dev.belikhun.luna.legacy.config;

import dev.belikhun.luna.legacy.logging.LunaLogger;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads a backend's config.yml, merging in any key the shipped defaults gained since
 * the file on disk was written. The caller supplies the path because that is the only
 * thing the platforms disagree about.
 */
public final class BackendCoreConfigLoader {
	private BackendCoreConfigLoader() {
	}

	/**
	 * @param resourceName the default's path inside the anchor's jar. Scope it by mod
	 *        id: every mod loader shares one class loader, so a bare "config.yml"
	 *        resolves to whichever luna jar comes first. That bit is not theoretical
	 *        - it is exactly how four mods on one server ended up reading LunaCore's
	 *        defaults into their own config directories.
	 */
	public static BackendCoreRuntimeConfig loadRuntimeConfig(
		Path configPath,
		Class<?> resourceAnchor,
		String resourceName,
		LunaLogger logger
	) {
		try {
			LunaYamlConfig.ensureFile(configPath, defaultStream(resourceAnchor, resourceName));

			Map<String, Object> current = new LinkedHashMap<String, Object>(LunaYamlConfig.loadMap(configPath));
			Map<String, Object> defaults = loadDefaults(resourceAnchor, resourceName);

			if (LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(configPath, current);
				logger.audit("Đã cập nhật config mặc định tại " + configPath.toAbsolutePath() + ".");
			}

			warnOnRetiredMessagingBlock(current, configPath, logger);

			BackendCoreRuntimeConfig runtimeConfig = parseRuntimeConfig(current);

			logger.audit(
				"Đã nạp config từ " + configPath.toAbsolutePath()
					+ ". heartbeat=" + runtimeConfig.heartbeatConfig().enabled()
					+ ", logging.level=" + runtimeConfig.loggingLevel()
			);

			return runtimeConfig;
		} catch (RuntimeException exception) {
			logger.error("Không thể nạp config. Dùng cấu hình mặc định tối thiểu.", exception);

			return BackendCoreRuntimeConfig.defaults();
		}
	}

	private static Supplier<InputStream> defaultStream(final Class<?> resourceAnchor, final String resourceName) {
		return new Supplier<InputStream>() {
			@Override
			public InputStream get() {
				return resourceAnchor.getClassLoader().getResourceAsStream(resourceName);
			}
		};
	}

	private static BackendCoreRuntimeConfig parseRuntimeConfig(Map<String, Object> rootConfig) {
		Map<String, Object> loggingConfig = ConfigValues.map(rootConfig, "logging");
		Map<String, Object> heartbeatConfig = ConfigValues.map(rootConfig, "heartbeat");
		Map<String, Object> heartbeatTransportLoggingConfig = ConfigValues.map(loggingConfig, "heartbeatTransport");
		Map<String, Object> pluginMessagingLoggingConfig = ConfigValues.map(loggingConfig, "pluginMessaging");

		return new BackendCoreRuntimeConfig(
			ConfigValues.booleanValue(loggingConfig, "ansi", true),
			ConfigValues.string(loggingConfig, "level", "INFO"),
			ConfigValues.booleanValue(pluginMessagingLoggingConfig, "enabled", false),
			new BackendCoreRuntimeConfig.HeartbeatConfig(
				ConfigValues.booleanValue(heartbeatConfig, "enabled", true),
				ConfigValues.string(heartbeatConfig, "endpoint", "http://127.0.0.1:32452/api/heartbeat"),
				ConfigValues.string(heartbeatConfig, "serverName", ""),
				ConfigValues.intValue(heartbeatConfig, "intervalSeconds", 5),
				ConfigValues.intValue(heartbeatConfig, "connectTimeoutMillis", 3000),
				ConfigValues.intValue(heartbeatConfig, "readTimeoutMillis", 3000),
				ConfigValues.booleanValue(heartbeatConfig, "streamEnabled", true),
				ConfigValues.booleanValue(heartbeatTransportLoggingConfig, "enabled", false)
			)
		);
	}

	/**
	 * A file written before the AMQP settings moved to the proxy still carries the
	 * block, and nothing removes it. Saying so once is cheaper than an operator
	 * editing a queue name that no longer reaches anything.
	 */
	private static void warnOnRetiredMessagingBlock(Map<String, Object> rootConfig, Path configPath, LunaLogger logger) {
		if (!ConfigValues.map(rootConfig, "messaging").containsKey("rabbitmq")) {
			return;
		}

		logger.warn(
			"Bỏ qua khối 'messaging.rabbitmq' trong " + configPath.toAbsolutePath()
				+ ": cấu hình AMQP nay lấy từ proxy qua heartbeat. Có thể xoá khối này."
		);
	}

	private static Map<String, Object> loadDefaults(Class<?> resourceAnchor, String resourceName) {
		InputStream stream = resourceAnchor.getClassLoader().getResourceAsStream(resourceName);

		if (stream == null) {
			return Collections.emptyMap();
		}

		try {
			return LunaYamlConfig.loadMap(stream);
		} catch (RuntimeException ignored) {
			return Collections.emptyMap();
		} finally {
			try {
				stream.close();
			} catch (Exception alsoIgnored) {
				// the defaults are already read or already lost
			}
		}
	}
}
