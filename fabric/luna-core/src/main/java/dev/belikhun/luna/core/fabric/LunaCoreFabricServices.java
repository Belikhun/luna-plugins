package dev.belikhun.luna.core.fabric;

import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.minecraft.server.MinecraftServer;

/**
 * What a luna fabric module gets from the core, mirroring Paper's
 * {@code LunaCoreServices}.
 *
 * The config and the database are the core's own, not a copy: a module reading
 * {@code strings.money.currencySymbol} or writing to {@code shop_transactions}
 * has to see the same file and the same connection every other module does, the
 * way it does on Paper.
 */
public record LunaCoreFabricServices(
	String modId,
	MinecraftServer server,
	DependencyManager dependencyManager,
	LunaLogger logger,
	BackendHeartbeatPublisher heartbeatPublisher,
	YamlConfigFile config,
	Database database
) {
}
