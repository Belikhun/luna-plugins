package dev.belikhun.luna.core.api.config.migration;

import dev.belikhun.luna.core.api.config.ConfigStore;

public interface ConfigMigration {
	default String namespace() {
		return "core";
	}

	int version();

	String name();

	void migrate(ConfigStore store);
}

