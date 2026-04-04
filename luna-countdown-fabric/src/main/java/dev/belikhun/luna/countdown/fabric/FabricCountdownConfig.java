package dev.belikhun.luna.countdown.fabric;

import dev.belikhun.luna.core.fabric.config.SimpleTomlConfig;

import java.io.IOException;
import java.nio.file.Path;

public record FabricCountdownConfig(
	boolean debugLogging,
	boolean messagingDebugLogging
) {
	public static FabricCountdownConfig load(Path path) throws IOException {
		SimpleTomlConfig config = SimpleTomlConfig.load(path);
		return new FabricCountdownConfig(
			config.getBoolean("debugLogging", false),
			config.getBoolean("messagingDebugLogging", false)
		);
	}

	public static String defaultToml() {
		return "# Luna Countdown Fabric configuration\n"
			+ "debugLogging = false\n"
			+ "messagingDebugLogging = false\n";
	}
}
