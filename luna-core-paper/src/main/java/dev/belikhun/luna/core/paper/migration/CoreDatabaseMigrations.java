package dev.belikhun.luna.core.paper.migration;

import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;

import java.util.List;

public final class CoreDatabaseMigrations {
	private CoreDatabaseMigrations() {
	}

	public static void register(DatabaseMigrator migrator) {
		migrator.register(new dev.belikhun.luna.core.api.database.migration.DatabaseMigration() {
			@Override
			public int version() {
				return 1;
			}

			@Override
			public String name() {
				return "create_user_profiles_table";
			}

			@Override
			public void migrate(dev.belikhun.luna.core.api.database.Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS user_profiles (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, total_play_seconds BIGINT NOT NULL DEFAULT 0, last_seen_at BIGINT NOT NULL DEFAULT 0)",
					List.of()
				);
			}
		});
	}
}


