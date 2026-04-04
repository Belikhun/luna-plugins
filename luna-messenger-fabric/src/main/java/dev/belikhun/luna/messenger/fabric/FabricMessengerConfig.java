package dev.belikhun.luna.messenger.fabric;

import dev.belikhun.luna.core.fabric.config.SimpleTomlConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public record FabricMessengerConfig(
	boolean debugLogging,
	boolean messagingDebugLogging,
	int requestTimeoutMillis,
	List<String> placeholderExportKeys
) {
	public static FabricMessengerConfig load(Path path) throws IOException {
		SimpleTomlConfig config = SimpleTomlConfig.load(path);
		return new FabricMessengerConfig(
			config.getBoolean("debugLogging", false),
			config.getBoolean("messagingDebugLogging", false),
			Math.max(1000, config.getInt("requestTimeoutMillis", 6000)),
			config.getStringList("placeholderExportKeys", List.of())
		);
	}

	public static String defaultToml() {
		return "# Luna Messenger Fabric configuration\n"
			+ "debugLogging = false\n"
			+ "messagingDebugLogging = false\n"
			+ "requestTimeoutMillis = 6000\n"
			+ "placeholderExportKeys = [\"luckperms_prefix\", \"luckperms_suffix\", \"luckperms_primary_group_name\", \"vault_prefix\", \"vault_suffix\", \"vault_primary_group\", \"player_displayname\"]\n";
	}
}
