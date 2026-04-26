package dev.belikhun.luna.core.fabric.lifecycle;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FabricModBootstrap {
	private FabricModBootstrap() {
	}

	public static LunaLogger initLogger(String loggerName, String scope, boolean debugEnabled) {
		FabricConsoleColorSupport.install();
		return LunaLogger.forLogger(FabricJulLogger.create(loggerName), true)
			.withDebug(debugEnabled)
			.scope(scope);
	}

	public static Path ensureConfigFile(String fileName, String defaultContent, LunaLogger logger) {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path configFile = configDir.resolve(fileName).normalize();

		try {
			Files.createDirectories(configFile.getParent());
			if (Files.notExists(configFile)) {
				Files.writeString(configFile, defaultContent, StandardCharsets.UTF_8);
				if (logger != null) {
					logger.audit("Đã tạo config mặc định tại " + configFile + ".");
				}
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Không thể tạo config mặc định tại " + configFile, exception);
		}

		return configFile;
	}
}
