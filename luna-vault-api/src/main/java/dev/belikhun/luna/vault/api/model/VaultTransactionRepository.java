package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
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
		int normalizedPageSize = Math.max(1, pageSize);
		int normalizedPage = Math.max(0, page);
		String playerKey = playerId.toString();
		long totalCount = database.first(
			"SELECT COUNT(*) AS total_count FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ?",
			List.of(playerKey, playerKey)
		).map(row -> rawLong(row.get("total_count"), 0L)).orElse(0L);
		int maxPage = totalCount <= 0 ? 0 : (int) ((totalCount - 1) / normalizedPageSize);
		int clampedPage = Math.min(normalizedPage, maxPage);
		List<Map<String, Object>> rows = database.query(
			"SELECT transaction_id, sender_uuid, sender_name, receiver_uuid, receiver_name, amount_minor, source_plugin, details, completed_at FROM vault_transactions WHERE sender_uuid = ? OR receiver_uuid = ? ORDER BY completed_at DESC LIMIT ? OFFSET ?",
			List.of(playerKey, playerKey, normalizedPageSize, clampedPage * normalizedPageSize)
		);
		List<VaultTransactionRecord> entries = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			entries.add(toRecord(row));
		}
		return new VaultTransactionPage(entries, clampedPage, normalizedPageSize, maxPage, (int) totalCount);
	}

	private VaultTransactionRecord toRecord(Map<String, Object> row) {
		return new VaultTransactionRecord(
			rawString(row.get("transaction_id")),
			rawUuid(row.get("sender_uuid")),
			rawString(row.get("sender_name")),
			rawUuid(row.get("receiver_uuid")),
			rawString(row.get("receiver_name")),
			rawLong(row.get("amount_minor"), 0L),
			rawString(row.get("source_plugin")),
			rawString(row.get("details")),
			rawLong(row.get("completed_at"), 0L)
		);
	}

	private long rawLong(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private String rawString(Object value) {
		if (value == null) {
			return null;
		}
		String string = String.valueOf(value);
		return string.isBlank() ? null : string;
	}

	private UUID rawUuid(Object value) {
		String raw = rawString(value);
		if (raw == null) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
