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
		StringBuilder url = new StringBuilder(type.jdbcPattern().formatted(host, port, name));
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
