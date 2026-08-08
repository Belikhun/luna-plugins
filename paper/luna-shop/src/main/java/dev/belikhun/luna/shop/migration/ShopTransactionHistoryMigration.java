package dev.belikhun.luna.shop.migration;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigration;

import java.util.List;

public final class ShopTransactionHistoryMigration implements DatabaseMigration {
	@Override
	public String namespace() {
		return "luna_shop";
	}

	@Override
	public int version() {
		return 1;
	}

	@Override
	public String name() {
		return "create_shop_transactions_table";
	}

	@Override
	public void migrate(Database database) {
		database.update(
			"CREATE TABLE IF NOT EXISTS shop_transactions (tx_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, action VARCHAR(16) NOT NULL, item_id VARCHAR(80) NOT NULL, category_id VARCHAR(80) NOT NULL, amount INT NOT NULL, unit_price DOUBLE NOT NULL, total_price DOUBLE NOT NULL, success INT NOT NULL, reason VARCHAR(255) NOT NULL, created_at BIGINT NOT NULL)",
			List.of()
		);
		database.update("CREATE INDEX IF NOT EXISTS idx_shop_tx_player_created ON shop_transactions (player_uuid, created_at)", List.of());
	}
}

