package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigration;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;

import java.util.List;

public final class VaultDatabaseMigrations {
	private VaultDatabaseMigrations() {
	}

	public static void register(DatabaseMigrator migrator) {
		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return "lunavault";
			}

			@Override
			public int version() {
				return 1;
			}

			@Override
			public String name() {
				return "create_vault_tables";
			}

			@Override
			public void migrate(Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS vault_accounts (player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, player_name VARCHAR(32) NOT NULL, balance_minor BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS vault_transactions (transaction_id VARCHAR(36) NOT NULL PRIMARY KEY, sender_uuid VARCHAR(36) NULL, sender_name VARCHAR(32) NULL, receiver_uuid VARCHAR(36) NULL, receiver_name VARCHAR(32) NULL, amount_minor BIGINT NOT NULL, source_plugin VARCHAR(80) NOT NULL, details TEXT NULL, completed_at BIGINT NOT NULL)",
					List.of()
				);
				database.update("CREATE INDEX IF NOT EXISTS vault_transactions_sender_idx ON vault_transactions (sender_uuid, completed_at)", List.of());
				database.update("CREATE INDEX IF NOT EXISTS vault_transactions_receiver_idx ON vault_transactions (receiver_uuid, completed_at)", List.of());
			}
		});

		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return "lunavault";
			}

			@Override
			public int version() {
				return 2;
			}

			@Override
			public String name() {
				return "create_vault_sync_outbox";
			}

			@Override
			public void migrate(Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS vault_sync_outbox (operation_id VARCHAR(36) NOT NULL PRIMARY KEY, actor_uuid VARCHAR(36) NULL, actor_name VARCHAR(32) NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(32) NULL, balance_minor BIGINT NOT NULL, source_plugin VARCHAR(80) NOT NULL, details TEXT NULL, state VARCHAR(16) NOT NULL, attempt_count INT NOT NULL, next_retry_at BIGINT NOT NULL, last_error TEXT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, acked_at BIGINT NULL)",
					List.of()
				);
				database.update("CREATE INDEX IF NOT EXISTS vault_sync_outbox_state_retry_idx ON vault_sync_outbox (state, next_retry_at)", List.of());
				database.update("CREATE INDEX IF NOT EXISTS vault_sync_outbox_player_idx ON vault_sync_outbox (player_uuid, created_at)", List.of());
				database.update("CREATE INDEX IF NOT EXISTS vault_sync_outbox_acked_idx ON vault_sync_outbox (state, acked_at)", List.of());
			}
		});
	}
}
