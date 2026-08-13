package dev.belikhun.luna.legacy.database;

import dev.belikhun.luna.legacy.logging.LunaLogger;

import java.util.Map;

/**
 * Open a database from a config section, for the platforms without Bukkit.
 *
 * {@link DatabaseManager} is the Paper-side equivalent and reads Bukkit's
 * {@code ConfigurationSection}; this takes the same block as a plain map. Both
 * answer a {@link NoopDatabase} when the block is missing or disabled, so the
 * repositories above them stay on their "database off" path instead of failing.
 */
public final class DatabaseConnector {
	private DatabaseConnector() {
	}

	/**
	 * @param section the config's {@code database} block
	 * @param logger where the outcome is reported; the connection itself is lazy,
	 *               so a wrong host surfaces on the first query, not here
	 * @return a live database, or a {@link NoopDatabase} when disabled or unusable
	 */
	public static Database connect(Map<String, Object> section, LunaLogger logger) {
		LunaLogger scoped = logger.scope("Database");
		DatabaseConfig config = DatabaseConfig.fromMap(section);

		if (!config.enabled()) {
			scoped.warn("Database bị tắt trong config.yml. Các tính năng cần database sẽ chạy ở chế độ hạn chế.");
			return new NoopDatabase();
		}

		try {
			Database database = new JdbcDatabase(config);
			scoped.success("Đã kết nối database bằng driver " + config.type().name() + ".");
			return database;
		} catch (RuntimeException exception) {
			scoped.error("Không thể kết nối database (" + config.type().name() + "). Chuyển sang chế độ không database.", exception);
			return new NoopDatabase();
		}
	}
}
