package dev.belikhun.luna.core.neoforge;

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

	public static AmqpMessagingConfig loadAmqpMessagingConfig(Class<?> resourceAnchor, LunaLogger logger) {
		try {
			LunaYamlConfig.ensureFile(CONFIG_PATH, () -> resourceAnchor.getClassLoader().getResourceAsStream(CONFIG_RESOURCE));
			Map<String, Object> current = new LinkedHashMap<>(LunaYamlConfig.loadMap(CONFIG_PATH));
			Map<String, Object> defaults = loadDefaults(resourceAnchor);
			if (LunaYamlConfig.mergeMissing(current, defaults)) {
				LunaYamlConfig.dumpMap(CONFIG_PATH, current);
				logger.audit("Đã cập nhật config NeoForge mặc định tại " + CONFIG_PATH.toAbsolutePath() + ".");
			}

			AmqpMessagingConfig amqpMessagingConfig = AmqpMessagingConfigCodec.fromConfigMap(current);
			logger.audit("Đã nạp AMQP config NeoForge từ " + CONFIG_PATH.toAbsolutePath() + ". enabled=" + amqpMessagingConfig.enabled());
			return amqpMessagingConfig;
		} catch (RuntimeException exception) {
			logger.error("Không thể nạp config NeoForge. Dùng AMQP disabled mặc định.", exception);
			return AmqpMessagingConfig.disabled();
		}
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
