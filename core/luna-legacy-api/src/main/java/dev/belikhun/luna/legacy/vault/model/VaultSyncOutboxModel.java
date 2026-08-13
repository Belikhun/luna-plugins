package dev.belikhun.luna.legacy.vault.model;

import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.model.Model;

public final class VaultSyncOutboxModel extends Model<VaultSyncOutboxModel> {
	public VaultSyncOutboxModel(Database database) {
		super(database);
	}

	@Override
	protected String table() {
		return "vault_sync_outbox";
	}

	@Override
	protected String primaryKey() {
		return "operation_id";
	}
}
