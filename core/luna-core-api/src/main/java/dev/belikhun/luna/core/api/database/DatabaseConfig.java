package dev.belikhun.luna.core.api.database;

import dev.belikhun.luna.core.api.config.ConfigValues;

import java.util.Map;

public record DatabaseConfig(
	boolean enabled,
	DatabaseType type,
	String host,
	int port,
	String name,
	String username,
	String password,
	Map<String, Object> options
) {
	/**
	 * Read the {@code database} block of a config as a plain map.
	 *
	 * {@code DatabaseManager} does the same job through Bukkit's
	 * {@code ConfigurationSection}, which a mod loader has no access to; the keys
	 * and their defaults are the ones every luna config.yml already writes, so a
	 * Paper plugin and a Fabric mod pointed at the same file connect the same way.
	 *
	 * @param section the {@code database} section, or null when the file has none
	 * @return the parsed settings, disabled when the section is missing or off
	 */
	public static DatabaseConfig fromMap(Map<String, Object> section) {
		if (section == null || section.isEmpty() || !ConfigValues.booleanValue(section, "enabled", false)) {
			return new DatabaseConfig(false, DatabaseType.SQLITE, "", 0, "", "", "", Map.of());
		}

		return new DatabaseConfig(
			true,
			DatabaseType.from(ConfigValues.string(section, "type", "sqlite")),
			ConfigValues.string(section, "host", "127.0.0.1"),
			ConfigValues.intValue(section, "port", 3306),
			ConfigValues.string(section, "name", "luna.db"),
			ConfigValues.string(section, "username", "root"),
			ConfigValues.stringPreserveWhitespace(section.get("password"), ""),
			ConfigValues.map(section, "options")
		);
	}

	public String jdbcUrl() {
		String baseUrl;
		if (type == DatabaseType.SQLITE) {
			String sqliteName = (name == null || name.isBlank()) ? "luna.db" : name.trim();
			if (!sqliteName.endsWith(".db") && !sqliteName.contains("/")) {
				sqliteName = sqliteName + ".db";
			}
			baseUrl = type.jdbcPattern().formatted(sqliteName);
			return baseUrl;
		}

		baseUrl = type.jdbcPattern().formatted(host, port, name);
		StringBuilder url = new StringBuilder(baseUrl);
		if (options != null && !options.isEmpty()) {
			url.append("?");
			boolean first = true;
			for (Map.Entry<String, Object> entry : options.entrySet()) {
				if (!first) {
					url.append("&");
				}

				url.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue()));
				first = false;
			}
		}

		return url.toString();
	}
}

