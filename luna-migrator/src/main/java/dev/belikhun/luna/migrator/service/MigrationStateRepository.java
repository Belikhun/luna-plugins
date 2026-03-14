package dev.belikhun.luna.migrator.service;

import dev.belikhun.luna.core.api.database.Database;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MigrationStateRepository {
	private final Database database;

	public MigrationStateRepository(Database database) {
		this.database = database;
	}

	public void ensureSchema() {
		database.update(
			"CREATE TABLE IF NOT EXISTS luna_auth_migration_state ("
				+ "online_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
				+ "old_username VARCHAR(32) NOT NULL, "
				+ "migrated_at BIGINT NOT NULL"
				+ ")",
			List.of()
		);
	}

	public boolean isMigrated(UUID onlineUuid, String oldUsername) {
		String normalized = normalize(oldUsername);
		if (isOnlineUuidMigrated(onlineUuid)) {
			return true;
		}
		return database.first("SELECT online_uuid FROM luna_auth_migration_state WHERE old_username = ?", List.of(normalized)).isPresent();
	}

	public boolean isOnlineUuidMigrated(UUID onlineUuid) {
		return database.first("SELECT online_uuid FROM luna_auth_migration_state WHERE online_uuid = ?", List.of(onlineUuid.toString())).isPresent();
	}

	public Optional<UUID> findOnlineUuidByOldUsername(String oldUsername) {
		return database.first("SELECT online_uuid FROM luna_auth_migration_state WHERE old_username = ?", List.of(normalize(oldUsername)))
			.map(row -> row.get("online_uuid"))
			.map(Object::toString)
			.map(UUID::fromString);
	}

	public Optional<String> findOldUsername(UUID onlineUuid) {
		return database.first("SELECT old_username FROM luna_auth_migration_state WHERE online_uuid = ?", List.of(onlineUuid.toString()))
			.map(row -> row.get("old_username"))
			.map(Object::toString);
	}

	public boolean hasEligibleSourceData(String oldUsername) {
		String normalized = normalize(oldUsername);
		if (normalized.isBlank()) {
			return false;
		}
		return database.first(
			"SELECT player_uuid FROM luna_auth_accounts WHERE LOWER(username) = ? AND password_hash <> ''",
			List.of(normalized)
		).isPresent();
	}

	public void markMigrated(UUID onlineUuid, String oldUsername) {
		database.update(
			"INSERT INTO luna_auth_migration_state (online_uuid, old_username, migrated_at) VALUES (?, ?, ?)",
			List.of(onlineUuid.toString(), normalize(oldUsername), System.currentTimeMillis())
		);
	}

	private String normalize(String username) {
		return username == null ? "" : username.trim().toLowerCase();
	}
}
