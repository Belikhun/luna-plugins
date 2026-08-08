package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.model.Model;

public final class VaultTransactionModel extends Model<VaultTransactionModel> {
	public VaultTransactionModel(Database database) {
		super(database);
	}

	@Override
	protected String table() {
		return "vault_transactions";
	}

	@Override
	protected String primaryKey() {
		return "transaction_id";
	}
}
