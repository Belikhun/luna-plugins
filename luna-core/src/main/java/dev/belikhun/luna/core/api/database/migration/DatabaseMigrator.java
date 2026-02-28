package dev.belikhun.luna.core.api.database.migration;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.exception.MigrationException;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DatabaseMigrator {
	private static final String MIGRATION_TABLE = "core_database_migrations";

	private final LunaLogger logger;
	private final Database database;
	private final List<DatabaseMigration> migrations;

	public DatabaseMigrator(Database database, LunaLogger logger) {
		this.logger = logger.scope("DatabaseMigration");
		this.database = database;
		this.migrations = new ArrayList<>();
	}

	public DatabaseMigrator register(DatabaseMigration migration) {
		if (migration == null) {
			throw new MigrationException("Database migration không được null.");
		}
		migrations.add(migration);
		return this;
	}

	public void migrate() {
		if (database instanceof NoopDatabase) {
			logger.warn("Bỏ qua database migration vì database đang tắt.");
			return;
		}

		try {
			ensureMigrationTable();
			Map<String, Set<Integer>> appliedVersions = appliedVersions();
			migrations.stream()
				.filter(this::hasNamespace)
				.sorted(Comparator.comparingInt(DatabaseMigration::version))
				.filter(migration -> !isApplied(appliedVersions, migration))
				.forEach(this::applyMigration);
		} catch (Exception exception) {
			throw new MigrationException("Không thể thực thi database migrations.", exception);
		}
	}

	public void migrateNamespace(String namespace) {
		if (database instanceof NoopDatabase) {
			logger.warn("Bỏ qua database migration vì database đang tắt.");
			return;
		}

		try {
			String normalizedNamespace = normalizeNamespace(namespace);
			ensureMigrationTable();
			Map<String, Set<Integer>> appliedVersions = appliedVersions();
			migrations.stream()
				.filter(migration -> normalizedNamespace.equals(normalizeNamespace(migration.namespace())))
				.sorted(Comparator.comparingInt(DatabaseMigration::version))
				.filter(migration -> !isApplied(appliedVersions, migration))
				.forEach(this::applyMigration);
		} catch (Exception exception) {
			throw new MigrationException("Không thể thực thi database migrations cho namespace " + namespace + ".", exception);
		}
	}

	private void ensureMigrationTable() {
		database.update(
			"CREATE TABLE IF NOT EXISTS " + MIGRATION_TABLE + " (namespace VARCHAR(80) NOT NULL, version INT NOT NULL, name VARCHAR(120) NOT NULL, applied_at BIGINT NOT NULL, PRIMARY KEY (namespace, version))",
			List.of()
		);
	}

	private Map<String, Set<Integer>> appliedVersions() {
		Map<String, Set<Integer>> versions = new HashMap<>();
		for (Map<String, Object> row : database.query("SELECT namespace, version FROM " + MIGRATION_TABLE, List.of())) {
			String namespace = rawToString(row.get("namespace"), "core");
			String key = normalizeNamespace(namespace);
			Object rawVersion = row.get("version");
			if (rawVersion instanceof Number number) {
				versions.computeIfAbsent(key, unused -> new HashSet<>()).add(number.intValue());
				continue;
			}

			if (rawVersion != null) {
				try {
					versions.computeIfAbsent(key, unused -> new HashSet<>()).add(Integer.parseInt(rawVersion.toString()));
				} catch (NumberFormatException ignored) {
				}
			}
		}

		return versions;
	}

	private void applyMigration(DatabaseMigration migration) {
		String namespace = normalizeNamespace(migration.namespace());
		logger.audit("Thực thi database migration [" + namespace + "] v" + migration.version() + " - " + migration.name());
		migration.migrate(database);
		database.update(
			"INSERT INTO " + MIGRATION_TABLE + " (namespace, version, name, applied_at) VALUES (?, ?, ?, ?)",
			List.of(namespace, migration.version(), migration.name(), Instant.now().toEpochMilli())
		);
		logger.success("Hoàn tất database migration [" + namespace + "] v" + migration.version());
	}

	private boolean hasNamespace(DatabaseMigration migration) {
		String namespace = normalizeNamespace(migration.namespace());
		return !namespace.isBlank();
	}

	private boolean isApplied(Map<String, Set<Integer>> appliedVersions, DatabaseMigration migration) {
		String namespace = normalizeNamespace(migration.namespace());
		Set<Integer> versions = appliedVersions.get(namespace);
		return versions != null && versions.contains(migration.version());
	}

	private String normalizeNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			return "core";
		}

		return namespace.trim().toLowerCase();
	}

	private String rawToString(Object value, String fallback) {
		return value == null ? fallback : String.valueOf(value);
	}
}
