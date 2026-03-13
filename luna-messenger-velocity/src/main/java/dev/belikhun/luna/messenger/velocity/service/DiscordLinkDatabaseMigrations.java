package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigration;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;

import java.util.List;

public final class DiscordLinkDatabaseMigrations {
	private DiscordLinkDatabaseMigrations() {
	}

	public static void register(DatabaseMigrator migrator) {
		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return "lunamessenger";
			}

			@Override
			public int version() {
				return 1;
			}

			@Override
			public String name() {
				return "create_discord_links_table";
			}

			@Override
			public void migrate(Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS messenger_discord_links ("
						+ "minecraft_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
						+ "minecraft_name VARCHAR(32) NOT NULL, "
						+ "discord_user_id VARCHAR(64) NOT NULL UNIQUE, "
						+ "discord_username VARCHAR(64) NOT NULL, "
						+ "linked_at BIGINT NOT NULL, "
						+ "updated_at BIGINT NOT NULL"
						+ ")",
					List.of()
				);
				database.update(
					"CREATE INDEX IF NOT EXISTS idx_messenger_discord_links_minecraft_name ON messenger_discord_links (minecraft_name)",
					List.of()
				);
			}
		});
	}
}
