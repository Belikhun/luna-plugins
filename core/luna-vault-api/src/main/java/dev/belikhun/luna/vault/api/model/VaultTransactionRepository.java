package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabasePage;
import dev.belikhun.luna.core.api.database.DatabaseValues;
import dev.belikhun.luna.vault.api.VaultTransactionKind;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.VaultTransactionSummary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the ledger table.
 *
 * Rows are only ever written by {@code VaultLedger}, inside its transactions;
 * everything here is a query. The column list is shared with the ledger so the
 * two cannot drift apart.
 */
public final class VaultTransactionRepository {
	/** Every column, in the order {@link #readRecord(ResultSet)} expects. */
	public static final String COLUMNS = "transaction_id, operation_id, kind, sender_uuid, sender_name, receiver_uuid, receiver_name, amount_minor, sender_balance_after, receiver_balance_after, source_plugin, details, completed_at";

	private final Database database;

	public VaultTransactionRepository(Database database) {
		this.database = database;
	}

	public VaultTransactionPage pageForPlayer(UUID playerId, int page, int pageSize) {
		String playerKey = playerId.toString();
		long totalCount = database.first(
			"SELECT COUNT(*) AS total_count FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ?",
			List.of(playerKey, playerKey)
		).map(row -> DatabaseValues.longValue(row.get("total_count"), 0L)).orElse(0L);
		DatabasePage databasePage = DatabasePage.of(totalCount, page, pageSize);
		List<Map<String, Object>> rows = database.query(
			"SELECT " + COLUMNS + " FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ? ORDER BY completed_at DESC, transaction_id DESC LIMIT ? OFFSET ?",
			List.of(playerKey, playerKey, databasePage.pageSize(), databasePage.offset())
		);
		List<VaultTransactionRecord> entries = new ArrayList<>();

		for (Map<String, Object> row : rows) {
			entries.add(toRecord(row));
		}

		return new VaultTransactionPage(entries, databasePage.page(), databasePage.pageSize(), databasePage.maxPage(), (int) totalCount);
	}

	/**
	 * The newest row that recorded a running balance for this player.
	 *
	 * Ordered by time then id so two rows in the same millisecond still pick one
	 * deterministically; the id is random, which is good enough for a tiebreak
	 * the audit only needs to be stable about.
	 */
	public Optional<VaultTransactionRecord> lastWithRunningBalance(UUID playerId) {
		if (playerId == null) {
			return Optional.empty();
		}

		String playerKey = playerId.toString();

		return database.first(
			"SELECT " + COLUMNS + " FROM vault_transactions WHERE (sender_uuid = ? AND sender_balance_after IS NOT NULL) OR (receiver_uuid = ? AND receiver_balance_after IS NOT NULL) ORDER BY completed_at DESC, transaction_id DESC LIMIT 1",
			List.of(playerKey, playerKey)
		).map(this::toRecord);
	}

	/** The row an operation id produced, when that operation was applied. */
	public Optional<VaultTransactionRecord> findByOperationId(UUID operationId) {
		if (operationId == null) {
			return Optional.empty();
		}

		return database.first(
			"SELECT " + COLUMNS + " FROM vault_transactions WHERE operation_id = ? LIMIT 1",
			List.of(operationId.toString())
		).map(this::toRecord);
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
			List.of(playerKey, playerKey, playerKey, playerKey, playerKey, playerKey)
		).map(row -> new VaultTransactionSummary(
			(int) DatabaseValues.longValue(row.get("entry_count"), 0L),
			DatabaseValues.longValue(row.get("received_minor"), 0L),
			DatabaseValues.longValue(row.get("sent_minor"), 0L),
			DatabaseValues.longValue(row.get("first_at"), 0L),
			DatabaseValues.longValue(row.get("last_at"), 0L)
		)).orElse(VaultTransactionSummary.empty());
	}

	/** Decode a row positioned on a result set selecting {@link #COLUMNS}. */
	public static VaultTransactionRecord readRecord(ResultSet rows) throws SQLException {
		return new VaultTransactionRecord(
			DatabaseValues.nonBlankOrNull(rows.getString("transaction_id")),
			DatabaseValues.uuidOrNull(rows.getString("operation_id")),
			VaultTransactionKind.parse(rows.getString("kind")),
			DatabaseValues.uuidOrNull(rows.getString("sender_uuid")),
			DatabaseValues.nonBlankOrNull(rows.getString("sender_name")),
			DatabaseValues.uuidOrNull(rows.getString("receiver_uuid")),
			DatabaseValues.nonBlankOrNull(rows.getString("receiver_name")),
			rows.getLong("amount_minor"),
			nullableLong(rows, "sender_balance_after"),
			nullableLong(rows, "receiver_balance_after"),
			DatabaseValues.nonBlankOrNull(rows.getString("source_plugin")),
			DatabaseValues.nonBlankOrNull(rows.getString("details")),
			rows.getLong("completed_at")
		);
	}

	private static Long nullableLong(ResultSet rows, String column) throws SQLException {
		long value = rows.getLong(column);

		if (rows.wasNull()) {
			return null;
		}

		return value;
	}

	private VaultTransactionRecord toRecord(Map<String, Object> row) {
		return new VaultTransactionRecord(
			DatabaseValues.nonBlankOrNull(row.get("transaction_id")),
			DatabaseValues.uuidOrNull(row.get("operation_id")),
			VaultTransactionKind.parse(DatabaseValues.string(row.get("kind"), "")),
			DatabaseValues.uuidOrNull(row.get("sender_uuid")),
			DatabaseValues.nonBlankOrNull(row.get("sender_name")),
			DatabaseValues.uuidOrNull(row.get("receiver_uuid")),
			DatabaseValues.nonBlankOrNull(row.get("receiver_name")),
			DatabaseValues.longValue(row.get("amount_minor"), 0L),
			nullableLong(row.get("sender_balance_after")),
			nullableLong(row.get("receiver_balance_after")),
			DatabaseValues.nonBlankOrNull(row.get("source_plugin")),
			DatabaseValues.nonBlankOrNull(row.get("details")),
			DatabaseValues.longValue(row.get("completed_at"), 0L)
		);
	}

	private static Long nullableLong(Object value) {
		if (value == null) {
			return null;
		}

		return DatabaseValues.longValue(value, 0L);
	}
}
