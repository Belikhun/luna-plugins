package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.DatabaseValues;
import dev.belikhun.luna.core.api.model.ModelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class VaultSyncOutboxRepository {
	public static final String STATE_PENDING = "PENDING";
	public static final String STATE_ACKED = "ACKED";
	public static final String STATE_FAILED = "FAILED";

	private final Database database;
	private final ModelRepository<VaultSyncOutboxModel> repository;

	public VaultSyncOutboxRepository(Database database) {
		this.database = database;
		this.repository = new ModelRepository<>(database, "vault_sync_outbox", "operation_id", () -> new VaultSyncOutboxModel(database));
	}

	public void enqueue(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long balanceMinor, String source, String details, long nowEpochMillis) {
		if (operationId == null || playerId == null) {
			return;
		}

		String operationKey = operationId.toString();
		Optional<VaultSyncOutboxModel> existing = repository.find(operationKey);
		if (existing.isPresent()) {
			return;
		}

		repository.newModel()
			.set("operation_id", operationKey)
			.set("actor_uuid", actorId == null ? null : actorId.toString())
			.set("actor_name", nullableName(actorName))
			.set("player_uuid", playerId.toString())
			.set("player_name", nullableName(playerName))
			.set("balance_minor", balanceMinor)
			.set("source_plugin", source == null || source.isBlank() ? "backend-sync" : source)
			.set("details", details)
			.set("state", STATE_PENDING)
			.set("attempt_count", 0)
			.set("next_retry_at", nowEpochMillis)
			.set("last_error", null)
			.set("created_at", nowEpochMillis)
			.set("updated_at", nowEpochMillis)
			.set("acked_at", null)
			.save();
	}

	public List<PendingSyncOperation> due(long nowEpochMillis, int limit) {
		int safeLimit = Math.max(1, limit);
		List<Map<String, Object>> rows = database.query(
			"SELECT operation_id, actor_uuid, actor_name, player_uuid, player_name, balance_minor, source_plugin, details, attempt_count, next_retry_at, created_at, updated_at FROM vault_sync_outbox WHERE state = ? AND next_retry_at <= ? ORDER BY next_retry_at ASC, created_at ASC LIMIT ?",
			List.of(STATE_PENDING, nowEpochMillis, safeLimit)
		);

		List<PendingSyncOperation> entries = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			UUID operationId = DatabaseValues.uuidOrNull(row.get("operation_id"));
			UUID playerId = DatabaseValues.uuidOrNull(row.get("player_uuid"));
			if (operationId == null || playerId == null) {
				continue;
			}

			entries.add(new PendingSyncOperation(
				operationId,
				DatabaseValues.uuidOrNull(row.get("actor_uuid")),
				DatabaseValues.nonBlankOrNull(row.get("actor_name")),
				playerId,
				DatabaseValues.nonBlankOrNull(row.get("player_name")),
				DatabaseValues.longValue(row.get("balance_minor"), 0L),
				DatabaseValues.nonBlankOrNull(row.get("source_plugin")),
				DatabaseValues.nonBlankOrNull(row.get("details")),
				DatabaseValues.intValue(row.get("attempt_count"), 0),
				DatabaseValues.longValue(row.get("next_retry_at"), nowEpochMillis),
				DatabaseValues.longValue(row.get("created_at"), nowEpochMillis),
				DatabaseValues.longValue(row.get("updated_at"), nowEpochMillis)
			));
		}

		return entries;
	}

	public void markAcked(UUID operationId, long nowEpochMillis) {
		if (operationId == null) {
			return;
		}

		database.update(
			"UPDATE vault_sync_outbox SET state = ?, acked_at = ?, last_error = NULL, updated_at = ? WHERE operation_id = ?",
			List.of(STATE_ACKED, nowEpochMillis, nowEpochMillis, operationId.toString())
		);
	}

	public void markRetry(UUID operationId, int attemptCount, long nextRetryAt, String error, long nowEpochMillis) {
		if (operationId == null) {
			return;
		}

		database.update(
			"UPDATE vault_sync_outbox SET state = ?, attempt_count = ?, next_retry_at = ?, last_error = ?, updated_at = ? WHERE operation_id = ?",
			List.of(STATE_PENDING, Math.max(1, attemptCount), Math.max(nowEpochMillis, nextRetryAt), trimError(error), nowEpochMillis, operationId.toString())
		);
	}

	public void markInFlight(UUID operationId, long lockUntilEpochMillis, long nowEpochMillis) {
		if (operationId == null) {
			return;
		}

		database.update(
			"UPDATE vault_sync_outbox SET next_retry_at = ?, updated_at = ? WHERE operation_id = ? AND state = ?",
			List.of(Math.max(nowEpochMillis, lockUntilEpochMillis), nowEpochMillis, operationId.toString(), STATE_PENDING)
		);
	}

	public void markFailed(UUID operationId, int attemptCount, String error, long nowEpochMillis) {
		if (operationId == null) {
			return;
		}

		database.update(
			"UPDATE vault_sync_outbox SET state = ?, attempt_count = ?, last_error = ?, updated_at = ? WHERE operation_id = ?",
			List.of(STATE_FAILED, Math.max(1, attemptCount), trimError(error), nowEpochMillis, operationId.toString())
		);
	}

	public int cleanupAckedOlderThan(long beforeEpochMillis, int limit) {
		int safeLimit = Math.max(1, limit);
		List<Map<String, Object>> rows = database.query(
			"SELECT operation_id FROM vault_sync_outbox WHERE state = ? AND acked_at IS NOT NULL AND acked_at < ? ORDER BY acked_at ASC LIMIT ?",
			List.of(STATE_ACKED, beforeEpochMillis, safeLimit)
		);
		if (rows.isEmpty()) {
			return 0;
		}

		int deleted = 0;
		for (Map<String, Object> row : rows) {
			String operationId = DatabaseValues.string(row.get("operation_id"), "");
			if (operationId.isBlank()) {
				continue;
			}
			deleted += database.update("DELETE FROM vault_sync_outbox WHERE operation_id = ?", List.of(operationId));
		}
		return deleted;
	}

	private String nullableName(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim();
		return normalized.isBlank() ? null : normalized;
	}

	private String trimError(String error) {
		if (error == null) {
			return null;
		}

		String normalized = error.trim();
		if (normalized.isBlank()) {
			return null;
		}

		return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
	}

	public record PendingSyncOperation(
		UUID operationId,
		UUID actorId,
		String actorName,
		UUID playerId,
		String playerName,
		long balanceMinor,
		String source,
		String details,
		int attemptCount,
		long nextRetryAt,
		long createdAt,
		long updatedAt
	) {
	}
}
