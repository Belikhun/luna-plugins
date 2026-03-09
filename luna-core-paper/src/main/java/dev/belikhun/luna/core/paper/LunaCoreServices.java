package dev.belikhun.luna.core.paper;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.database.DatabaseManager;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.help.HelpRegistry;
import dev.belikhun.luna.core.api.http.HttpServerManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.migration.MigrationManager;
import dev.belikhun.luna.core.api.profile.UserProfileRepository;
import dev.belikhun.luna.core.api.string.MessageFormatter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public record LunaCoreServices(
	Plugin plugin,
	ConfigStore configStore,
	DatabaseManager databaseManager,
	DependencyManager dependencyManager,
	MigrationManager migrationManager,
	LunaLogger logger,
	MessageFormatter messageFormatter,
	HelpRegistry helpRegistry,
	HttpServerManager httpServerManager,
	UserProfileRepository userProfileRepository,
	PluginMessageBus<Player, Player> pluginMessaging
) {
}


