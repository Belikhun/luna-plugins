package dev.belikhun.luna.core.api.migration;

import dev.belikhun.luna.core.api.config.migration.ConfigMigration;
import dev.belikhun.luna.core.api.config.migration.ConfigStoreMigrator;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigration;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import org.bukkit.plugin.Plugin;

public final class MigrationManager {
	private final Plugin plugin;
	private final ConfigStoreMigrator configMigrator;
	private final DatabaseMigrator databaseMigrator;

	public MigrationManager(Plugin plugin, ConfigStoreMigrator configMigrator, DatabaseMigrator databaseMigrator) {
		this.plugin = plugin;
		this.configMigrator = configMigrator;
		this.databaseMigrator = databaseMigrator;
	}

	public Plugin plugin() {
		return plugin;
	}

	public void registerConfigMigration(ConfigMigration migration) {
		configMigrator.register(migration);
		configMigrator.migrateNamespace(migration.namespace());
	}

	public void registerDatabaseMigration(DatabaseMigration migration) {
		databaseMigrator.register(migration);
		databaseMigrator.migrateNamespace(migration.namespace());
	}

	public ConfigStoreMigrator configMigrator() {
		return configMigrator;
	}

	public DatabaseMigrator databaseMigrator() {
		return databaseMigrator;
	}
}
