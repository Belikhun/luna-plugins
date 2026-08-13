package dev.belikhun.luna.legacy.vault.model;

import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.model.Model;

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
