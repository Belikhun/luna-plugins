package dev.belikhun.luna.core.api.config.migration;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.exception.MigrationException;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ConfigStoreMigrator {
	private static final String VERSION_PATH_PREFIX = "core.meta.configMigrations";

	private final LunaLogger logger;
	private final ConfigStore store;
	private final List<ConfigMigration> migrations;

	public ConfigStoreMigrator(ConfigStore store, LunaLogger logger) {
		this.logger = logger.scope("ConfigMigration");
		this.store = store;
		this.migrations = new ArrayList<>();
	}

	public ConfigStoreMigrator register(ConfigMigration migration) {
		if (migration == null) {
			throw new MigrationException("Config migration không được null.");
		}
		migrations.add(migration);
		return this;
	}

	public void migrate() {
		try {
			migrations.stream()
				.sorted(Comparator.comparingInt(ConfigMigration::version))
				.filter(this::shouldApply)
				.forEach(migration -> applyMigration(migration));
		} catch (Exception exception) {
			throw new MigrationException("Không thể thực thi config migrations.", exception);
		}
	}

	public void migrateNamespace(String namespace) {
		try {
			String normalizedNamespace = normalizeNamespace(namespace);
			migrations.stream()
				.filter(migration -> normalizedNamespace.equals(normalizeNamespace(migration.namespace())))
				.sorted(Comparator.comparingInt(ConfigMigration::version))
				.filter(this::shouldApply)
				.forEach(this::applyMigration);
		} catch (Exception exception) {
			throw new MigrationException("Không thể thực thi config migrations cho namespace " + namespace + ".", exception);
		}
	}

	private void applyMigration(ConfigMigration migration) {
		String namespace = normalizeNamespace(migration.namespace());
		logger.audit("Thực thi config migration [" + namespace + "] v" + migration.version() + " - " + migration.name());
		migration.migrate(store);
		store.get(versionPath(namespace)).set(migration.version());
		store.save();
		logger.success("Hoàn tất config migration [" + namespace + "] v" + migration.version());
	}

	private boolean shouldApply(ConfigMigration migration) {
		String namespace = normalizeNamespace(migration.namespace());
		int currentVersion = store.get(versionPath(namespace)).asInt(0);
		return migration.version() > currentVersion;
	}

	private String versionPath(String namespace) {
		return VERSION_PATH_PREFIX + "." + namespace;
	}

	private String normalizeNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			return "core";
		}

		return namespace.trim().toLowerCase();
	}
}

