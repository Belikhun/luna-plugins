package dev.belikhun.luna.core.api.config;

import dev.belikhun.luna.core.api.exception.ConfigStoreException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class ConfigStore {
	private final Plugin plugin;
	private final File file;
	private YamlConfiguration configuration;

	private ConfigStore(Plugin plugin, File file, YamlConfiguration configuration) {
		this.plugin = plugin;
		this.file = file;
		this.configuration = configuration;
	}

	public static ConfigStore of(Plugin plugin, String relativePath) {
		File file = new File(plugin.getDataFolder(), relativePath);
		try {
			if (!plugin.getDataFolder().exists()) {
				Files.createDirectories(plugin.getDataFolder().toPath());
			}
			LunaYamlConfig.ensureFile(file.toPath(), () -> plugin.getResource(relativePath));
		} catch (IOException exception) {
			throw new ConfigStoreException("Cannot initialize config store: " + relativePath, exception);
		}

		YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
		return new ConfigStore(plugin, file, configuration);
	}

	public ConfigNode get(String path) {
		return new ConfigNode(this, normalize(path));
	}

	public YamlConfiguration raw() {
		return configuration;
	}

	public void save() {
		try {
			configuration.save(file);
		} catch (IOException exception) {
			throw new ConfigStoreException("Cannot save config store: " + file.getName(), exception);
		}
	}

	public void reload() {
		configuration = YamlConfiguration.loadConfiguration(file);
	}

	String normalize(String path) {
		return path == null ? "" : path.trim().replace(" ", "");
	}

	Plugin plugin() {
		return plugin;
	}
}

