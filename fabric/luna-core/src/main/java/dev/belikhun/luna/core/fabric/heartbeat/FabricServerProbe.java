package dev.belikhun.luna.core.fabric.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendServerProbe;
import dev.belikhun.luna.core.api.heartbeat.ServerWorldStats;
import dev.belikhun.luna.core.mc.heartbeat.LevelStats;
import dev.belikhun.luna.core.fabric.compat.GameVersion;
import dev.belikhun.luna.core.fabric.compat.Guarded;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * What a heartbeat needs to know about a Fabric dedicated server.
 *
 * Every game call here is compiled, not looked up by name: Fabric remaps the
 * server to intermediary at runtime, so {@code getMethod("getMotd")} finds
 * nothing while the compiled call site is remapped along with the mod and keeps
 * working. Each one is guarded on its own, so a value a future version takes
 * away costs one blank field in the console rather than the whole beat.
 */
public final class FabricServerProbe implements BackendServerProbe {
	private final MinecraftServer server;
	private final TickRateMonitor tickRate;

	public FabricServerProbe(MinecraftServer server, TickRateMonitor tickRate) {
		this.server = server;
		this.tickRate = tickRate;
	}

	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
	}

	@Override
	public void execute(Runnable task) {
		server.execute(task);
	}

	@Override
	public boolean isServerThread() {
		return Guarded.booleanValue(server::isSameThread, false);
	}

	@Override
	public String serverModName() {
		return Guarded.value(server::getServerModName, "Fabric");
	}

	@Override
	public String gameVersion() {
		// the loader knows this without touching a game class, which is both
		// cheaper and immune to the version rename it would otherwise report on
		return GameVersion.display();
	}

	@Override
	public int port() {
		return Guarded.intValue(server::getPort, 0);
	}

	@Override
	public int onlinePlayers() {
		return PlayerLookup.all(server).size();
	}

	@Override
	public int maxPlayers() {
		return Guarded.intValue(server::getMaxPlayers, 0);
	}

	@Override
	public String motd() {
		return Guarded.value(server::getMotd, "");
	}

	@Override
	public boolean whitelistEnforced() {
		return Guarded.booleanValue(server::isEnforceWhitelist, false);
	}

	@Override
	public double tps() {
		return tickRate.tps();
	}

	@Override
	public java.util.List<ServerWorldStats> worlds() {
		return LevelStats.scan(server);
	}
}
