package dev.belikhun.luna.core.velocity.players;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigration;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;

import java.util.List;

/**
 * Schema for the proxy-side player directory: profiles, play sessions, the
 * chat/command log and the moderation log.
 *
 * These tables are written by {@link VelocityPlayerRecordStore} and read by the
 * console through the player directory HTTP endpoints. They live in the shared
 * MariaDB database so a proxy restart no longer forgets who has ever played.
 */
public final class PlayerDatabaseMigrations {
	public static final String NAMESPACE = "lunacoreplayers";

	private PlayerDatabaseMigrations() {
	}

	public static void register(DatabaseMigrator migrator) {
		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return NAMESPACE;
			}

			@Override
			public int version() {
				return 1;
			}

			@Override
			public String name() {
				return "create_player_directory_tables";
			}

			@Override
			public void migrate(Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_player_profiles ("
						+ "uuid VARCHAR(36) PRIMARY KEY, "
						+ "username VARCHAR(32) NOT NULL, "
						+ "username_lower VARCHAR(32) NOT NULL, "
						+ "first_seen_at BIGINT NOT NULL DEFAULT 0, "
						+ "last_seen_at BIGINT NOT NULL DEFAULT 0, "
						+ "last_server VARCHAR(64) NOT NULL DEFAULT '', "
						+ "last_address VARCHAR(64) NOT NULL DEFAULT '', "
						+ "last_client_version VARCHAR(32) NOT NULL DEFAULT '', "
						+ "online_mode TINYINT NOT NULL DEFAULT 0, "
						+ "session_count INT NOT NULL DEFAULT 0, "
						+ "total_play_millis BIGINT NOT NULL DEFAULT 0, "
						+ "skin_texture TEXT NULL, "
						+ "skin_signature TEXT NULL, "
						+ "KEY idx_profiles_username (username_lower), "
						+ "KEY idx_profiles_last_seen (last_seen_at)"
						+ ")",
					List.of()
				);

				database.update(
					"CREATE TABLE IF NOT EXISTS luna_player_sessions ("
						+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
						+ "uuid VARCHAR(36) NOT NULL, "
						+ "username VARCHAR(32) NOT NULL, "
						+ "server VARCHAR(64) NOT NULL DEFAULT '', "
						+ "connected_at BIGINT NOT NULL, "
						+ "disconnected_at BIGINT NULL, "
						+ "duration_millis BIGINT NOT NULL DEFAULT 0, "
						+ "KEY idx_sessions_uuid (uuid, connected_at), "
						+ "KEY idx_sessions_open (disconnected_at)"
						+ ")",
					List.of()
				);

				database.update(
					"CREATE TABLE IF NOT EXISTS luna_player_chat ("
						+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
						+ "uuid VARCHAR(36) NOT NULL, "
						+ "username VARCHAR(32) NOT NULL, "
						+ "server VARCHAR(64) NOT NULL DEFAULT '', "
						+ "type VARCHAR(10) NOT NULL, "
						+ "content TEXT NOT NULL, "
						+ "at BIGINT NOT NULL, "
						+ "KEY idx_chat_uuid (uuid, at), "
						+ "KEY idx_chat_at (at)"
						+ ")",
					List.of()
				);

				database.update(
					"CREATE TABLE IF NOT EXISTS luna_player_moderation ("
						+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
						+ "target_uuid VARCHAR(36) NOT NULL DEFAULT '', "
						+ "target_name VARCHAR(32) NOT NULL DEFAULT '', "
						+ "action VARCHAR(32) NOT NULL, "
						+ "actor VARCHAR(64) NOT NULL DEFAULT '', "
						+ "reason TEXT NULL, "
						+ "server VARCHAR(64) NOT NULL DEFAULT '', "
						+ "details TEXT NULL, "
						+ "at BIGINT NOT NULL, "
						+ "KEY idx_moderation_target (target_uuid, at), "
						+ "KEY idx_moderation_at (at)"
						+ ")",
					List.of()
				);
			}
		});

		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return NAMESPACE;
			}

			@Override
			public int version() {
				return 2;
			}

			@Override
			public String name() {
				return "create_network_ip_bans_table";
			}

			@Override
			public void migrate(Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_network_ip_bans ("
						+ "ip VARCHAR(64) PRIMARY KEY, "
						+ "reason TEXT NULL, "
						+ "actor VARCHAR(64) NOT NULL DEFAULT '', "
						+ "created_at BIGINT NOT NULL, "
						+ "expires_at BIGINT NULL, "
						+ "hits BIGINT NOT NULL DEFAULT 0, "
						+ "last_hit_at BIGINT NULL"
						+ ")",
					List.of()
				);
			}
		});
	}
}
