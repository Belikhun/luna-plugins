package dev.belikhun.luna.core.api.database.migration;

import dev.belikhun.luna.core.api.database.Database;

public interface DatabaseMigration {
	default String namespace() {
		return "core";
	}

	int version();

	String name();

	void migrate(Database database);
}

