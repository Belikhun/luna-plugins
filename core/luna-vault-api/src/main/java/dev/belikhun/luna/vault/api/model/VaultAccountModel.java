package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.model.Model;

public final class VaultAccountModel extends Model<VaultAccountModel> {
	public VaultAccountModel(Database database) {
		super(database);
	}

	@Override
	protected String table() {
		return "vault_accounts";
	}

	@Override
	protected String primaryKey() {
		return "player_uuid";
	}
}
