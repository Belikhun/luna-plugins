package dev.belikhun.luna.legacy.vault.model;

import java.util.Arrays;

import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.database.DatabasePage;
import dev.belikhun.luna.legacy.database.DatabaseValues;
import dev.belikhun.luna.legacy.vault.VaultTransactionPage;
import dev.belikhun.luna.legacy.vault.VaultTransactionRecord;
import dev.belikhun.luna.legacy.vault.VaultTransactionSummary;

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
			Arrays.<Object>asList(playerKey, playerKey)
		).map(row -> DatabaseValues.longValue(row.get("total_count"), 0L)).orElse(0L);
		DatabasePage databasePage = DatabasePage.of(totalCount, page, pageSize);
		List<Map<String, Object>> rows = database.query(
			"SELECT transaction_id, sender_uuid, sender_name, receiver_uuid, receiver_name, amount_minor, source_plugin, details, completed_at FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ? ORDER BY completed_at DESC LIMIT ? OFFSET ?",
			Arrays.<Object>asList(playerKey, playerKey, databasePage.pageSize(), databasePage.offset())
		);
		List<VaultTransactionRecord> entries = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			entries.add(toRecord(row));
		}
		return new VaultTransactionPage(entries, databasePage.page(), databasePage.pageSize(), databasePage.maxPage(), (int) totalCount);
	}

	/**
	 * Lifetime totals for a player, in one aggregate query.
	 *
	 * Rows naming the same player on both sides count towards neither total: an
	 * admin adjusting their own balance is written with the actor opposite the
	 * target, and counting that as both earned and spent would inflate the two
	 * figures by the same amount for money that never moved between anyone. Those
	 * rows still count towards {@code entry_count}, because they did happen.
	 */
	public VaultTransactionSummary summaryForPlayer(UUID playerId) {
		if (playerId == null) {
			return VaultTransactionSummary.empty();
		}

		String playerKey = playerId.toString();

		return database.first(
			"SELECT COUNT(*) AS entry_count, "
				+ "COALESCE(SUM(CASE WHEN receiver_uuid = ? AND (sender_uuid IS NULL OR sender_uuid <> ?) THEN amount_minor ELSE 0 END), 0) AS received_minor, "
				+ "COALESCE(SUM(CASE WHEN sender_uuid = ? AND (receiver_uuid IS NULL OR receiver_uuid <> ?) THEN amount_minor ELSE 0 END), 0) AS sent_minor, "
				+ "COALESCE(MIN(completed_at), 0) AS first_at, "
				+ "COALESCE(MAX(completed_at), 0) AS last_at "
				+ "FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ?",
			Arrays.<Object>asList(playerKey, playerKey, playerKey, playerKey, playerKey, playerKey)
		).map(row -> new VaultTransactionSummary(
			(int) DatabaseValues.longValue(row.get("entry_count"), 0L),
			DatabaseValues.longValue(row.get("received_minor"), 0L),
			DatabaseValues.longValue(row.get("sent_minor"), 0L),
			DatabaseValues.longValue(row.get("first_at"), 0L),
			DatabaseValues.longValue(row.get("last_at"), 0L)
		)).orElse(VaultTransactionSummary.empty());
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
