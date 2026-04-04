package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabasePage;
import dev.belikhun.luna.core.api.database.DatabaseValues;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VaultTransactionRepository {
	private final Database database;

	public VaultTransactionRepository(Database database) {
		this.database = database;
	}

	public VaultTransactionModel newModel() {
		return new VaultTransactionModel(database);
	}

	public VaultTransactionPage pageForPlayer(UUID playerId, int page, int pageSize) {
		String playerKey = playerId.toString();
		long totalCount = database.first(
			"SELECT COUNT(*) AS total_count FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ?",
			List.of(playerKey, playerKey)
		).map(row -> DatabaseValues.longValue(row.get("total_count"), 0L)).orElse(0L);
		DatabasePage databasePage = DatabasePage.of(totalCount, page, pageSize);
		List<Map<String, Object>> rows = database.query(
			"SELECT transaction_id, sender_uuid, sender_name, receiver_uuid, receiver_name, amount_minor, source_plugin, details, completed_at FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ? ORDER BY completed_at DESC LIMIT ? OFFSET ?",
			List.of(playerKey, playerKey, databasePage.pageSize(), databasePage.offset())
		);
		List<VaultTransactionRecord> entries = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			entries.add(toRecord(row));
		}
		return new VaultTransactionPage(entries, databasePage.page(), databasePage.pageSize(), databasePage.maxPage(), (int) totalCount);
	}

	private VaultTransactionRecord toRecord(Map<String, Object> row) {
		return new VaultTransactionRecord(
			DatabaseValues.nonBlankOrNull(row.get("transaction_id")),
			DatabaseValues.uuidOrNull(row.get("sender_uuid")),
			DatabaseValues.nonBlankOrNull(row.get("sender_name")),
			DatabaseValues.uuidOrNull(row.get("receiver_uuid")),
			DatabaseValues.nonBlankOrNull(row.get("receiver_name")),
			DatabaseValues.longValue(row.get("amount_minor"), 0L),
			DatabaseValues.nonBlankOrNull(row.get("source_plugin")),
			DatabaseValues.nonBlankOrNull(row.get("details")),
			DatabaseValues.longValue(row.get("completed_at"), 0L)
		);
	}
}
