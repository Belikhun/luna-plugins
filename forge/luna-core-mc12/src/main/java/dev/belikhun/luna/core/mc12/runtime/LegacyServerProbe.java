package dev.belikhun.luna.core.mc12.runtime;

import dev.belikhun.luna.legacy.heartbeat.BackendServerProbe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;

import java.nio.file.Path;

/**
 * Everything the heartbeat needs that only a running 1.12.2 server can answer.
 *
 * Every call here is an MCP name that RFG reobfuscates to its SRG id on the way into
 * the jar. That is why the probe exists at all rather than the publisher reading the
 * server object reflectively: a lookup by readable method name works in a dev
 * workspace and fails on every live server, which is the worst possible failure shape
 * - it passes the test you run and breaks the thing you ship.
 */
public final class LegacyServerProbe implements BackendServerProbe {
	/** A tick is 50ms; the server cannot exceed 20 TPS however fast it ticks. */
	private static final double MILLIS_PER_TICK = 50D;
	private static final double MAX_TPS = 20D;

	private final MinecraftServer server;
	private final Path configDir;

	public LegacyServerProbe(MinecraftServer server, Path configDir) {
		this.server = server;
		this.configDir = configDir;
	}

	@Override
	public Path configDir() {
		return configDir;
	}

	@Override
	public void execute(Runnable task) {
		server.addScheduledTask(task);
	}

	@Override
	public boolean isServerThread() {
		return server.isCallingFromMinecraftThread();
	}

	@Override
	public String serverModName() {
		return "forge";
	}

	@Override
	public String gameVersion() {
		return server.getMinecraftVersion();
	}

	@Override
	public int port() {
		return server.getServerPort();
	}

	@Override
	public int onlinePlayers() {
		PlayerList players = server.getPlayerList();

		return players == null ? 0 : players.getCurrentPlayerCount();
	}

	@Override
	public int maxPlayers() {
		PlayerList players = server.getPlayerList();

		return players == null ? 0 : players.getMaxPlayers();
	}

	@Override
	public String motd() {
		String motd = server.getMOTD();

		return motd == null ? "" : motd;
	}

	@Override
	public boolean whitelistEnforced() {
		PlayerList players = server.getPlayerList();

		return players != null && players.isWhiteListEnabled();
	}

	/**
	 * Tick rate from the server's own rolling window of tick durations.
	 *
	 * 1.12.2 has no TPS accessor - `tickTimeArray` is the raw ring buffer of the last
	 * 100 ticks in nanoseconds, and the mean of it is what every 1.12.2 TPS command
	 * has always computed. Entries are zero until the buffer has filled, so a mean
	 * over the whole array during the first seconds of boot reads as an impossibly
	 * fast server; only the ticks that actually happened are counted.
	 */
	@Override
	public double tps() {
		long[] tickTimes = server.tickTimeArray;

		if (tickTimes == null || tickTimes.length == 0) {
			return MAX_TPS;
		}

		long total = 0L;
		int counted = 0;

		for (long tickTime : tickTimes) {
			if (tickTime <= 0L) {
				continue;
			}

			total += tickTime;
			counted += 1;
		}

		if (counted == 0) {
			return MAX_TPS;
		}

		double meanMillis = (total / (double) counted) / 1_000_000D;

		return Math.min(MAX_TPS, 1000D / Math.max(MILLIS_PER_TICK, meanMillis));
	}
}
