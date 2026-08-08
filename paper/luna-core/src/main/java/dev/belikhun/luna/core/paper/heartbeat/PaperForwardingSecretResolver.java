package dev.belikhun.luna.core.paper.heartbeat;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;

public final class PaperForwardingSecretResolver {
	private PaperForwardingSecretResolver() {
	}

	public static String resolve(Plugin plugin, LunaLogger logger) {
		Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
		File[] candidates = new File[] {
			root.resolve("config").resolve("paper-global.yml").toFile(),
			root.resolve("paper-global.yml").toFile()
		};

		for (File candidate : candidates) {
			if (!candidate.exists() || !candidate.isFile()) {
				continue;
			}

			YamlConfiguration config = YamlConfiguration.loadConfiguration(candidate);
			String secret = config.getString("proxies.velocity.secret", "");
			if (secret == null || secret.isBlank()) {
				secret = config.getString("settings.velocity-support.secret", "");
			}

			if (secret != null && !secret.isBlank()) {
				return secret.trim();
			}
		}

		logger.warn("Không thể tìm forwarding secret trong paper-global.yml, heartbeat sẽ không hoạt động.");
		return "";
	}
}
