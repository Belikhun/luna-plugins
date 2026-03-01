package dev.belikhun.luna.core.api.database;

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
