package dev.belikhun.luna.core.paper.lifecycle;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class PaperPluginBootstrap {
	private PaperPluginBootstrap() {
	}

	public static boolean ensurePluginEnabled(JavaPlugin plugin, String dependencyName, String failureMessage) {
		if (plugin.getServer().getPluginManager().isPluginEnabled(dependencyName)) {
			return true;
		}

		plugin.getLogger().severe(failureMessage);
		plugin.getServer().getPluginManager().disablePlugin(plugin);
		return false;
	}

	public static LunaLogger initLogger(JavaPlugin plugin, String scope) {
		return LunaLogger.forPlugin(plugin, true).scope(scope);
	}

	public static void ensureDataFolder(JavaPlugin plugin) {
		File folder = plugin.getDataFolder();
		if (!folder.exists()) {
			folder.mkdirs();
		}
	}

	public static void ensureResourceFile(JavaPlugin plugin, String resourcePath, LunaLogger logger) {
		File file = new File(plugin.getDataFolder(), resourcePath);
		if (file.exists()) {
			return;
		}

		plugin.saveResource(resourcePath, false);
		if (logger != null) {
			logger.audit("Đã tạo " + resourcePath + " mặc định.");
		}
	}
}
