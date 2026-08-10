package dev.belikhun.luna.core.mc;

import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import net.minecraft.server.MinecraftServer;

/**
 * What a luna neoforge module gets from the core, mirroring Paper's
 * {@code LunaCoreServices} and the fabric record of the same name.
 *
 * The config and the database are the core's own, not a copy: a module reading
 * {@code strings.money.currencySymbol} or writing to {@code shop_transactions}
 * has to see the same file and the same connection every other module does.
 */
public record LunaCoreServices(
	String modId,
	MinecraftServer server,
	DependencyManager dependencyManager,
	LunaLogger logger,
	BackendHeartbeatPublisher heartbeatPublisher,
	YamlConfigFile config,
	Database database
) {
}
