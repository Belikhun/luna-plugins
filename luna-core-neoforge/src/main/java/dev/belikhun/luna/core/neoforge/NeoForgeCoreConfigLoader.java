package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfigCodec;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NeoForgeCoreConfigLoader {
	private static final String CONFIG_RESOURCE = "config.yml";
	private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("lunacore").resolve("config.yml");

	private NeoForgeCoreConfigLoader() {
	}

	public static NeoForgeCoreRuntimeConfig loadRuntimeConfig(Class<?> resourceAnchor, LunaLogger logger) {
		try {
			LunaYamlConfig.ensureFile(CONFIG_PATH, () -> resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE));
			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(CONFIG_PATH));
			Map<String, Object> defaults = loadDefaults(resourceAnchor);
			if (LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(CONFIG_PATH, current);
				logger.audit("Đã cập nhật config NeoForge mặc định tại " + CONFIG_PATH.toAbsolutePath() + ".");
			}

			NeoForgeCoreRuntimeConfig runtimeConfig = parseRuntimeConfig(current);
			logger.audit(
				"Đã nạp config NeoForge từ " + CONFIG_PATH.toAbsolutePath()
					+ ". heartbeat=" + runtimeConfig.heartbeatConfig().enabled()
					+ ", amqp=" + runtimeConfig.amqpMessagingConfig().enabled()
					+ ", logging.level=" + runtimeConfig.loggingLevel()
			);
			return runtimeConfig;
		} catch (RuntimeException exception) {
			logger.error("Không thể nạp config NeoForge. Dùng cấu hình mặc định tối thiểu.", exception);
			return new NeoForgeCoreRuntimeConfig(
				true,
				"INFO",
				AmqpMessagingConfig.disabled(),
				NeoForgeCoreRuntimeConfig.HeartbeatConfig.defaults()
			);
		}
	}

	public static AmqpMessagingConfig loadAmqpMessagingConfig(Class<?> resourceAnchor, LunaLogger logger) {
		return loadRuntimeConfig(resourceAnchor, logger).amqpMessagingConfig();
	}

	private static NeoForgeCoreRuntimeConfig parseRuntimeConfig(Map<String, Object> rootConfig) {
		Map<String, Object> loggingConfig = ConfigValues.map(rootConfig, "logging");
		Map<String, Object> heartbeatConfig = ConfigValues.map(rootConfig, "heartbeat");
		Map<String, Object> heartbeatTransportLoggingConfig = ConfigValues.map(loggingConfig, "heartbeatTransport");
		return new NeoForgeCoreRuntimeConfig(
			ConfigValues.booleanValue(loggingConfig, "ansi", true),
			ConfigValues.string(loggingConfig, "level", "INFO"),
			AmqpMessagingConfigCodec.fromConfigMap(rootConfig),
			new NeoForgeCoreRuntimeConfig.HeartbeatConfig(
				ConfigValues.booleanValue(heartbeatConfig, "enabled", true),
				ConfigValues.string(heartbeatConfig, "endpoint", "http://127.0.0.1:32452/api/heartbeat"),
				ConfigValues.string(heartbeatConfig, "serverName", ""),
				ConfigValues.intValue(heartbeatConfig, "intervalSeconds", 5),
				ConfigValues.intValue(heartbeatConfig, "connectTimeoutMillis", 3000),
				ConfigValues.intValue(heartbeatConfig, "readTimeoutMillis", 3000),
				ConfigValues.booleanValue(heartbeatTransportLoggingConfig, "enabled", false)
			)
		);
	}

	private static Map<String, Object> loadDefaults(Class<?> resourceAnchor) {
		try (InputStream stream = resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
			if (stream == null) {
				return Map.of();
			}

			return LunaYamlConfig.loadMap(stream);
		} catch (Exception exception) {
			return Map.of();
		}
	}
}
