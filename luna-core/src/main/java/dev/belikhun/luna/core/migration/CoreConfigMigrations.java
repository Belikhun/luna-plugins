package dev.belikhun.luna.core.migration;

import dev.belikhun.luna.core.api.config.migration.ConfigMigration;
import dev.belikhun.luna.core.api.config.migration.ConfigStoreMigrator;

public final class CoreConfigMigrations {
	private CoreConfigMigrations() {
	}

	public static void register(ConfigStoreMigrator migrator) {
		migrator.register(new ConfigMigration() {
			@Override
			public int version() {
				return 1;
			}

			@Override
			public String name() {
				return "initialize_core_defaults";
			}

			@Override
			public void migrate(dev.belikhun.luna.core.api.config.ConfigStore store) {
				ensureDefault(store, "database.enabled", true);
				ensureDefault(store, "database.type", "mariadb");
				ensureDefault(store, "database.host", "127.0.0.1");
				ensureDefault(store, "database.port", 3306);
				ensureDefault(store, "database.name", "luna");
				ensureDefault(store, "database.username", "root");
				ensureDefault(store, "database.password", "");
				ensureDefault(store, "database.options.useSSL", false);
				ensureDefault(store, "database.options.serverTimezone", "UTC");
				ensureDefault(store, "http.enabled", false);
				ensureDefault(store, "http.host", "0.0.0.0");
				ensureDefault(store, "http.port", 8080);
				ensureDefault(store, "http.pathPrefix", "/api");
				ensureDefault(store, "help.gui.title", "<gold>Hướng Dẫn Lệnh");
				ensureDefault(store, "help.gui.size", 54);
				ensureDefault(store, "help.gui.pageSize", 45);
				ensureDefault(store, "help.search.prompt", "🔍 Nhập từ khóa tìm kiếm trên chat");
				ensureDefault(store, "help.search.cancelKeywords", java.util.List.of("huy", "cancel"));
				ensureDefault(store, "strings.money.currencySymbol", "₫");
				ensureDefault(store, "strings.money.grouping", true);
				ensureDefault(store, "logging.ansi", true);
				ensureDefault(store, "logging.defaultScope", "LunaCore");
				ensureDefault(store, "logging.level", "INFO");
			}
		});

		migrator.register(new ConfigMigration() {
			@Override
			public int version() {
				return 2;
			}

			@Override
			public String name() {
				return "add_logging_defaults";
			}

			@Override
			public void migrate(dev.belikhun.luna.core.api.config.ConfigStore store) {
				ensureDefault(store, "logging.ansi", true);
				ensureDefault(store, "logging.defaultScope", "LunaCore");
				ensureDefault(store, "logging.level", "INFO");
			}
		});
	}

	private static void ensureDefault(dev.belikhun.luna.core.api.config.ConfigStore store, String path, Object value) {
		if (store.get(path).value() == null) {
			store.get(path).set(value);
		}
	}
}
