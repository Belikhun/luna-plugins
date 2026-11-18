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

		migrator.register(new DatabaseMigration() {
			@Override
			public String namespace() {
				return NAMESPACE;
			}

			@Override
			public int version() {
				return 3;
			}

			@Override
			public String name() {
				return "index_chat_by_server";
			}

			@Override
			public void migrate(Database database) {
				// the per-backend chat log reads newest-first within one server, which
				// the (uuid, at) and (at) keys cannot serve without a full scan
				database.update(
					"CREATE INDEX IF NOT EXISTS idx_chat_server ON luna_player_chat (server, at)",
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
				return 4;
			}

			@Override
			public String name() {
				return "create_network_player_bans_table";
			}

			@Override
			public void migrate(Database database) {
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_network_bans ("
						+ "username_lower VARCHAR(32) PRIMARY KEY, "
						+ "username VARCHAR(32) NOT NULL, "
						+ "uuid VARCHAR(36) NOT NULL DEFAULT '', "
						+ "reason TEXT NULL, "
						+ "actor VARCHAR(64) NOT NULL DEFAULT '', "
						+ "created_at BIGINT NOT NULL, "
						+ "expires_at BIGINT NULL, "
						+ "hits BIGINT NOT NULL DEFAULT 0, "
						+ "last_hit_at BIGINT NULL, "
						+ "KEY idx_network_bans_uuid (uuid)"
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
				return 5;
			}

			@Override
			public String name() {
				return "create_firewall_tables";
			}

			@Override
			public void migrate(Database database) {
				// a ban now says where it came from and how many times the address was
				// banned, which is what escalates an automatic ban's length
				database.update(
					"ALTER TABLE luna_network_ip_bans ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'manual'",
					List.of()
				);
				database.update(
					"ALTER TABLE luna_network_ip_bans ADD COLUMN IF NOT EXISTS strikes INT NOT NULL DEFAULT 1",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_network_ip_ban_history ("
						+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
						+ "ip VARCHAR(64) NOT NULL, "
						+ "reason TEXT NULL, "
						+ "actor VARCHAR(64) NOT NULL DEFAULT '', "
						+ "source VARCHAR(32) NOT NULL DEFAULT 'manual', "
						+ "strikes INT NOT NULL DEFAULT 1, "
						+ "created_at BIGINT NOT NULL, "
						+ "expires_at BIGINT NULL, "
						+ "hits BIGINT NOT NULL DEFAULT 0, "
						+ "ended_at BIGINT NOT NULL, "
						+ "ended VARCHAR(16) NOT NULL, "
						+ "ended_by VARCHAR(64) NOT NULL DEFAULT '', "
						+ "KEY idx_ban_history_ip (ip, ended_at), "
						+ "KEY idx_ban_history_ended (ended_at)"
						+ ")",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_firewall_rules ("
						+ "id VARCHAR(32) PRIMARY KEY, "
						+ "action VARCHAR(8) NOT NULL, "
						+ "enabled TINYINT NOT NULL DEFAULT 1, "
						+ "addresses TEXT NULL, "
						+ "countries TEXT NULL, "
						+ "note TEXT NULL, "
						+ "created_by VARCHAR(64) NOT NULL DEFAULT '', "
						+ "created_at BIGINT NOT NULL, "
						+ "updated_at BIGINT NOT NULL, "
						+ "hits BIGINT NOT NULL DEFAULT 0, "
						+ "last_hit_at BIGINT NULL"
						+ ")",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_firewall_settings ("
						+ "k VARCHAR(64) PRIMARY KEY, "
						+ "v TEXT NULL"
						+ ")",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_firewall_countries ("
						+ "code CHAR(2) PRIMARY KEY, "
						+ "release_tag VARCHAR(16) NOT NULL DEFAULT '', "
						+ "prefixes MEDIUMTEXT NULL, "
						+ "updated_at BIGINT NOT NULL"
						+ ")",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_firewall_refusals ("
						+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
						+ "at BIGINT NOT NULL, "
						+ "ip VARCHAR(64) NOT NULL, "
						+ "username VARCHAR(32) NOT NULL DEFAULT '', "
						+ "kind VARCHAR(16) NOT NULL, "
						+ "reason VARCHAR(32) NOT NULL, "
						+ "detail VARCHAR(255) NOT NULL DEFAULT '', "
						+ "count INT NOT NULL DEFAULT 1, "
						+ "KEY idx_refusals_at (at), "
						+ "KEY idx_refusals_ip (ip, at)"
						+ ")",
					List.of()
				);
				database.update(
					"CREATE TABLE IF NOT EXISTS luna_firewall_hourly ("
						+ "hour BIGINT NOT NULL, "
						+ "reason VARCHAR(32) NOT NULL, "
						+ "count BIGINT NOT NULL DEFAULT 0, "
						+ "PRIMARY KEY (hour, reason)"
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
				return 6;
			}

			@Override
			public String name() {
				return "add_chat_channel_columns";
			}

			@Override
			public void migrate(Database database) {
				// Nullable with no default on purpose: every line recorded before this
				// has no channel, and NULL is what says "not known" instead of
				// claiming one. Adding a nullable column is an instant, metadata-only
				// change on MariaDB 10.3+, so the size of the chat log does not matter.
				database.update(
					"ALTER TABLE luna_player_chat ADD COLUMN IF NOT EXISTS channel VARCHAR(16) NULL",
					List.of()
				);
				database.update(
					"ALTER TABLE luna_player_chat ADD COLUMN IF NOT EXISTS channel_target_uuid VARCHAR(36) NULL",
					List.of()
				);
				database.update(
					"ALTER TABLE luna_player_chat ADD COLUMN IF NOT EXISTS channel_target_name VARCHAR(32) NULL",
					List.of()
				);
			}
		});
	}
}
