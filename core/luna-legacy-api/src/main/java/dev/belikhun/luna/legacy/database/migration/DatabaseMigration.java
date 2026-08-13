package dev.belikhun.luna.legacy.database.migration;

import dev.belikhun.luna.legacy.database.Database;

public interface DatabaseMigration {
	default String namespace() {
		return "core";
	}

	int version();

	String name();

	void migrate(Database database);
}

