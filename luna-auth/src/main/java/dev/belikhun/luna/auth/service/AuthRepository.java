package dev.belikhun.luna.auth.service;

import dev.belikhun.luna.auth.model.AuthAccount;
import dev.belikhun.luna.core.api.database.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AuthRepository {
	private final Database database;

	public AuthRepository(Database database) {
		this.database = database;
	}

	public void ensureSchema() {
		database.update(
			"CREATE TABLE IF NOT EXISTS luna_auth_accounts ("
				+ "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
				+ "username VARCHAR(32) NOT NULL, "
				+ "password_hash VARCHAR(255) NOT NULL, "
				+ "last_ip VARCHAR(64) NOT NULL, "
				+ "failed_attempts INT NOT NULL, "
				+ "lockout_until BIGINT NOT NULL, "
				+ "last_login_at BIGINT NOT NULL, "
				+ "created_at BIGINT NOT NULL, "
				+ "updated_at BIGINT NOT NULL"
				+ ")",
			List.of()
		);
		database.update(
			"CREATE TABLE IF NOT EXISTS luna_auth_login_history ("
				+ "player_uuid VARCHAR(36) NOT NULL, "
				+ "username VARCHAR(32) NOT NULL, "
				+ "ip_address VARCHAR(64) NOT NULL, "
				+ "success BOOLEAN NOT NULL, "
				+ "reason VARCHAR(120) NOT NULL, "
				+ "login_at BIGINT NOT NULL"
				+ ")",
			List.of()
		);
		database.update(
			"CREATE TABLE IF NOT EXISTS luna_auth_audit_history ("
				+ "player_uuid VARCHAR(36) NOT NULL, "
				+ "ip_address VARCHAR(64) NOT NULL, "
				+ "event_type VARCHAR(80) NOT NULL, "
				+ "result VARCHAR(16) NOT NULL, "
				+ "reason VARCHAR(160) NOT NULL, "
				+ "actor VARCHAR(40) NOT NULL, "
				+ "created_at BIGINT NOT NULL"
				+ ")",
			List.of()
		);
		database.update(
			"CREATE TABLE IF NOT EXISTS luna_auth_session_history ("
				+ "player_uuid VARCHAR(36) NOT NULL, "
				+ "username VARCHAR(32) NOT NULL, "
				+ "ip_address VARCHAR(64) NOT NULL, "
				+ "event_type VARCHAR(64) NOT NULL, "
				+ "detail VARCHAR(255) NOT NULL, "
				+ "happened_at BIGINT NOT NULL"
				+ ")",
			List.of()
		);
		database.update(
			"CREATE TABLE IF NOT EXISTS luna_auth_uuid_claims ("
				+ "offline_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
				+ "online_uuid VARCHAR(36) NOT NULL, "
				+ "username VARCHAR(32) NOT NULL, "
				+ "updated_at BIGINT NOT NULL"
				+ ")",
			List.of()
		);
	}

	public void claimOfflineUuidMapping(String username, UUID offlineUuid, UUID onlineUuid, long now) {
		String normalized = normalizeUsername(username);
		int updated = database.update(
			"UPDATE luna_auth_uuid_claims SET online_uuid = ?, username = ?, updated_at = ? WHERE offline_uuid = ?",
			List.of(onlineUuid.toString(), normalized, now, offlineUuid.toString())
		);
		if (updated > 0) {
			return;
		}

		database.update(
			"INSERT INTO luna_auth_uuid_claims (offline_uuid, online_uuid, username, updated_at) VALUES (?, ?, ?, ?)",
			List.of(offlineUuid.toString(), onlineUuid.toString(), normalized, now)
		);
	}

	public Optional<UUID> findClaimedOnlineUuid(UUID offlineUuid, String username) {
		return database.first(
			"SELECT online_uuid FROM luna_auth_uuid_claims WHERE offline_uuid = ? AND username = ?",
			List.of(offlineUuid.toString(), normalizeUsername(username))
		).map(row -> row.get("online_uuid"))
			.map(Object::toString)
			.map(UUID::fromString);
	}

	public Optional<AuthAccount> find(UUID playerUuid) {
		return database.first(
			"SELECT player_uuid, username, password_hash, last_ip, failed_attempts, lockout_until, last_login_at, created_at, updated_at "
				+ "FROM luna_auth_accounts WHERE player_uuid = ?",
			List.of(playerUuid.toString())
		).map(this::mapAccount);
	}

	public Optional<AuthAccount> findByUsername(String username) {
		return database.first(
			"SELECT player_uuid, username, password_hash, last_ip, failed_attempts, lockout_until, last_login_at, created_at, updated_at "
				+ "FROM luna_auth_accounts WHERE LOWER(username) = ?",
			List.of(username.toLowerCase())
		).map(this::mapAccount);
	}

	public void upsert(AuthAccount account) {
		int updated = database.update(
			"UPDATE luna_auth_accounts SET username = ?, password_hash = ?, last_ip = ?, failed_attempts = ?, lockout_until = ?, last_login_at = ?, updated_at = ? WHERE player_uuid = ?",
			List.of(
				account.username(),
				account.passwordHash(),
				account.lastIp(),
				account.failedAttempts(),
				account.lockoutUntilEpochMillis(),
				account.lastLoginAtEpochMillis(),
				account.updatedAtEpochMillis(),
				account.playerUuid().toString()
			)
		);
		if (updated > 0) {
			return;
		}

		database.update(
			"INSERT INTO luna_auth_accounts (player_uuid, username, password_hash, last_ip, failed_attempts, lockout_until, last_login_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			List.of(
				account.playerUuid().toString(),
				account.username(),
				account.passwordHash(),
				account.lastIp(),
				account.failedAttempts(),
				account.lockoutUntilEpochMillis(),
				account.lastLoginAtEpochMillis(),
				account.createdAtEpochMillis(),
				account.updatedAtEpochMillis()
			)
		);
	}

	public void deletePassword(UUID playerUuid, String fallbackIp, String fallbackUsername, long now) {
		AuthAccount current = find(playerUuid).orElse(new AuthAccount(playerUuid, fallbackUsername, "", fallbackIp, 0, 0L, 0L, now, now));
		upsert(new AuthAccount(
			current.playerUuid(),
			current.username(),
			"",
			current.lastIp(),
			0,
			0L,
			current.lastLoginAtEpochMillis(),
			current.createdAtEpochMillis(),
			now
		));
	}

	public void recordAttemptLegacy(UUID playerUuid, String username, String ip, boolean success, String reason, long now) {
		database.update(
			"INSERT INTO luna_auth_login_history (player_uuid, username, ip_address, success, reason, login_at) VALUES (?, ?, ?, ?, ?, ?)",
			List.of(playerUuid.toString(), username, ip, success, reason, now)
		);
	}

	public void recordAuditEvent(UUID playerUuid, String ipAddress, String eventType, String result, String reason, String actor, long now) {
		database.update(
			"INSERT INTO luna_auth_audit_history (player_uuid, ip_address, event_type, result, reason, actor, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			List.of(playerUuid.toString(), ipAddress, eventType, result, reason, actor, now)
		);
	}

	public void cleanupHistory(long minKeepEpochMillis) {
		database.update(
			"DELETE FROM luna_auth_login_history WHERE login_at < ?",
			List.of(minKeepEpochMillis)
		);
		database.update(
			"DELETE FROM luna_auth_audit_history WHERE created_at < ?",
			List.of(minKeepEpochMillis)
		);
		database.update(
			"DELETE FROM luna_auth_session_history WHERE happened_at < ?",
			List.of(minKeepEpochMillis)
		);
	}

	public void recordSessionEvent(UUID playerUuid, String username, String ipAddress, String eventType, String detail, long now) {
		database.update(
			"INSERT INTO luna_auth_session_history (player_uuid, username, ip_address, event_type, detail, happened_at) VALUES (?, ?, ?, ?, ?, ?)",
			List.of(playerUuid.toString(), username, ipAddress, eventType, detail, now)
		);
	}

	public List<LoginHistoryEntry> listLoginHistory(UUID playerUuid, int limit) {
		List<Map<String, Object>> rows = database.query(
			"SELECT ip_address, event_type, result, reason, actor, created_at FROM luna_auth_audit_history WHERE player_uuid = ? ORDER BY created_at DESC",
			List.of(playerUuid.toString())
		);
		List<LoginHistoryEntry> results = new ArrayList<>();
		int max = Math.max(1, limit);
		for (Map<String, Object> row : rows) {
			results.add(new LoginHistoryEntry(
				rawString(row.get("ip_address"), ""),
				rawString(row.get("event_type"), "UNKNOWN"),
				rawString(row.get("result"), "UNKNOWN"),
				rawString(row.get("reason"), "UNKNOWN"),
				rawString(row.get("actor"), "SYSTEM"),
				rawLong(row.get("created_at"), 0L)
			));
			if (results.size() >= max) {
				break;
			}
		}
		return results;
	}

	public List<SessionEventEntry> listSessionEvents(UUID playerUuid, int limit) {
		List<Map<String, Object>> rows = database.query(
			"SELECT username, ip_address, event_type, detail, happened_at FROM luna_auth_session_history WHERE player_uuid = ? ORDER BY happened_at DESC",
			List.of(playerUuid.toString())
		);
		List<SessionEventEntry> results = new ArrayList<>();
		int max = Math.max(1, limit);
		for (Map<String, Object> row : rows) {
			results.add(new SessionEventEntry(
				rawString(row.get("username"), ""),
				rawString(row.get("ip_address"), ""),
				rawString(row.get("event_type"), "UNKNOWN"),
				rawString(row.get("detail"), ""),
				rawLong(row.get("happened_at"), 0L)
			));
			if (results.size() >= max) {
				break;
			}
		}
		return results;
	}

	private AuthAccount mapAccount(Map<String, Object> row) {
		return new AuthAccount(
			UUID.fromString(rawString(row.get("player_uuid"), "00000000-0000-0000-0000-000000000000")),
			rawString(row.get("username"), ""),
			rawString(row.get("password_hash"), ""),
			rawString(row.get("last_ip"), ""),
			rawInt(row.get("failed_attempts"), 0),
			rawLong(row.get("lockout_until"), 0L),
			rawLong(row.get("last_login_at"), 0L),
			rawLong(row.get("created_at"), 0L),
			rawLong(row.get("updated_at"), 0L)
		);
	}

	private int rawInt(Object value, int fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.toString());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private long rawLong(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value.toString());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private String rawString(Object value, String fallback) {
		return value == null ? fallback : value.toString();
	}

	private String normalizeUsername(String username) {
		return username == null ? "" : username.trim().toLowerCase();
	}

	public record LoginHistoryEntry(String ipAddress, String eventType, String result, String reason, String actor, long createdAtEpochMillis) {
	}

	public record SessionEventEntry(String username, String ipAddress, String eventType, String detail, long happenedAtEpochMillis) {
	}
}
