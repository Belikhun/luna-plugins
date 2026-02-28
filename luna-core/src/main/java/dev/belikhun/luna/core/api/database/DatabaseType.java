package dev.belikhun.luna.core.api.database;

import dev.belikhun.luna.core.api.exception.DatabaseConfigurationException;

public enum DatabaseType {
	MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s"),
	MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://%s:%d/%s"),
	POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql://%s:%d/%s");

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
		return switch (normalized) {
			case "MYSQL" -> MYSQL;
			case "MARIADB", "MARIA_DB" -> MARIADB;
			case "POSTGRES", "POSTGRESQL" -> POSTGRESQL;
			default -> throw new DatabaseConfigurationException("Unsupported database type: " + value);
		};
	}
}
