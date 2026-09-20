package dev.belikhun.luna.vault.api.ledger;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabaseConfig;
import dev.belikhun.luna.core.api.database.DatabaseType;
import dev.belikhun.luna.core.api.database.JdbcDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/** A fresh, migrated SQLite file per test. */
final class TestDatabases {
	private TestDatabases() {
	}

	static Database freshSqlite() throws Exception {
		Path file = Files.createTempFile("luna-vault-test-", ".db");
		file.toFile().deleteOnExit();

		DatabaseConfig config = new DatabaseConfig(true, DatabaseType.SQLITE, "", 0, file.toAbsolutePath().toString(), "", "", Map.of());
		Database database = new JdbcDatabase(config);

		DatabaseMigrator migrator = new DatabaseMigrator(database, LunaLogger.forLogger(Logger.getLogger("test"), false));
		VaultDatabaseMigrations.register(migrator);
		migrator.migrateNamespace("lunavault");

		return database;
	}
}
