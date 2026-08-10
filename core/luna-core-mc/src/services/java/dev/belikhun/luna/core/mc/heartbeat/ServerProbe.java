package dev.belikhun.luna.core.mc.heartbeat;

import dev.belikhun.luna.core.api.heartbeat.BackendServerProbe;
import dev.belikhun.luna.core.api.util.Reflect;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import java.nio.file.Path;

/**
 * What a heartbeat needs to know about a mod-loader dedicated server.
 *
 * The config directory is handed in rather than looked up, because that is the
 * one fact only the loader knows (each spells its own {@code FMLPaths}) and it is
 * all that stood between this class and being loader-free.
 */
public final class ServerProbe implements BackendServerProbe {
	private final MinecraftServer server;
	private final Path configDir;

	public ServerProbe(MinecraftServer server, Path configDir) {
		this.server = server;
		this.configDir = configDir;
	}

	@Override
	public Path configDir() {
		return configDir;
	}

	@Override
	public void execute(Runnable task) {
		server.execute(task);
	}

	@Override
	public boolean isServerThread() {
		return server.isSameThread();
	}

	@Override
	public String serverModName() {
		return server.getServerModName();
	}

	@Override
	public String gameVersion() {
		return SharedConstants.getCurrentVersion().getName();
	}

	@Override
	public int port() {
		return server.getPort();
	}

	@Override
	public int onlinePlayers() {
		PlayerList playerList = server.getPlayerList();
		return playerList == null ? 0 : playerList.getPlayers().size();
	}

	@Override
	public int maxPlayers() {
		return server.getMaxPlayers();
	}

	@Override
	public String motd() {
		return server.getMotd();
	}

	@Override
	public boolean whitelistEnforced() {
		return server.isEnforceWhitelist();
	}

	@Override
	public double tps() {
		// the accessor keeps being renamed between game versions, and NeoForge runs
		// Mojang's names at runtime, so asking for each of them by name costs one
		// failed lookup and keeps working across the range
		for (String methodName : new String[] {"getAverageTickTime", "getCurrentSmoothedTickTime", "getTickTime"}) {
			Double averageTickTime = Reflect.callDouble(server, methodName);
			if (averageTickTime == null || averageTickTime <= 0D) {
				continue;
			}

			return clampTps(1000D / averageTickTime);
		}

		if (Reflect.field(server, "tickTimes") instanceof long[] values && values.length > 0) {
			long total = 0L;
			int samples = 0;
			for (long value : values) {
				if (value <= 0L) {
					continue;
				}
				total += value;
				samples++;
			}

			if (samples > 0) {
				double averageTickTimeMillis = (total / (double) samples) / 1_000_000D;
				if (averageTickTimeMillis > 0D) {
					return clampTps(1000D / averageTickTimeMillis);
				}
			}
		}

		return 20D;
	}

	private double clampTps(double tps) {
		return Math.max(0D, Math.min(20D, tps));
	}
}
