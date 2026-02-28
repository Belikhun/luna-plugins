package dev.belikhun.luna.core.api.database;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DatabaseManager {
	private final LunaLogger logger;
	private final ConfigStore configStore;
	private Database database;

	public DatabaseManager(ConfigStore configStore, LunaLogger logger) {
		this.logger = logger.scope("Database");
		this.configStore = configStore;
		this.database = new NoopDatabase();
	}

	public void connectFromConfig() {
		logger.audit("Đang tải cấu hình kết nối cơ sở dữ liệu.");
		ConfigurationSection section = configStore.raw().getConfigurationSection("database");
		if (section == null || !section.getBoolean("enabled", false)) {
			logger.warn("Database bị tắt trong config.yml");
			return;
		}

		String typeName = section.getString("type", "mariadb");
		DatabaseType type = DatabaseType.from(typeName);
		Map<String, Object> options = readOptions(section.getConfigurationSection("options"));
		DatabaseConfig config = new DatabaseConfig(
			true,
			type,
			section.getString("host", "127.0.0.1"),
			section.getInt("port", type == DatabaseType.POSTGRESQL ? 5432 : 3306),
			section.getString("name", "luna"),
			section.getString("username", "root"),
			section.getString("password", ""),
			options
		);

		this.database = new JdbcDatabase(config);
		logger.success("Đã kết nối database bằng driver " + type.name());
	}

	public Database getDatabase() {
		return database;
	}

	public boolean isEnabled() {
		return !(database instanceof NoopDatabase);
	}

	public void close() {
		logger.audit("Đang đóng kết nối database API.");
		database.close();
	}

	private Map<String, Object> readOptions(ConfigurationSection optionsSection) {
		if (optionsSection == null) {
			return Collections.emptyMap();
		}

		Map<String, Object> options = new LinkedHashMap<>();
		for (String key : optionsSection.getKeys(false)) {
			options.put(key, optionsSection.get(key));
		}

		return options;
	}
}
