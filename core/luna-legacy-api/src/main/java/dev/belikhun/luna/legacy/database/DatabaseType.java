package dev.belikhun.luna.legacy.database;

import dev.belikhun.luna.legacy.exception.DatabaseConfigurationException;

public enum DatabaseType {
	MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s"),
	MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://%s:%d/%s"),
	SQLITE("org.sqlite.JDBC", "jdbc:sqlite:%s");

	private final String driverClass;
	private final String jdbcPattern;

	DatabaseType(String driverClass, String jdbcPattern) {
		this.driverClass = driverClass;
		this.jdbcPattern = jdbcPattern;
	}

	public String driverClass() {
		return driverClass;
	}

	public String jdbcPattern() {
		return jdbcPattern;
	}

	public static DatabaseType from(String value) {
		String normalized = value == null ? "" : value.trim().toUpperCase();
		switch (normalized) {
			case "MYSQL":
				return MYSQL;

			case "MARIADB":
			case "MARIA_DB":
				return MARIADB;

			case "SQLITE":
			case "SQLITE3":
				return SQLITE;

			default:
				throw new DatabaseConfigurationException("Unsupported database type: " + value);
		}
	}
}

