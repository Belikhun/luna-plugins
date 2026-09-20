package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigration;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.exception.DatabaseException;

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

		// Version 2 created the backend sync outbox, which the ledger rewrite
		// removed; the number stays taken so existing installs do not replay it.
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
			}
		});

		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return "lunavault";
			}

			@Override
			public int version() {
				return 3;
			}

			@Override
			public String name() {
				return "ledger_idempotency_and_running_balances";
			}

			/**
			 * The columns the ledger writes, each added on its own because SQLite
			 * takes one per statement and neither engine offers a portable
			 * "add if missing". A column that already exists (an install that ran
			 * a partial attempt) is skipped by catching the engine's complaint.
			 */
			@Override
			public void migrate(Database database) {
				addColumn(database, "vault_transactions", "operation_id VARCHAR(36) NULL");
				addColumn(database, "vault_transactions", "kind VARCHAR(16) NULL");
				addColumn(database, "vault_transactions", "sender_balance_after BIGINT NULL");
				addColumn(database, "vault_transactions", "receiver_balance_after BIGINT NULL");
				database.update("CREATE UNIQUE INDEX IF NOT EXISTS vault_transactions_operation_idx ON vault_transactions (operation_id)", List.of());
				database.update("CREATE INDEX IF NOT EXISTS vault_accounts_balance_idx ON vault_accounts (balance_minor, player_uuid)", List.of());
				database.update("DROP TABLE IF EXISTS vault_sync_outbox", List.of());
			}
		});
	}

	private static void addColumn(Database database, String table, String definition) {
		try {
			database.update("ALTER TABLE " + table + " ADD COLUMN " + definition, List.of());
		} catch (DatabaseException exception) {
			String message = String.valueOf(exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage()).toLowerCase();

			if (message.contains("duplicate column") || message.contains("already exists")) {
				return;
			}

			throw exception;
		}
	}
}
