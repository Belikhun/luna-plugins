package dev.belikhun.luna.core.mc.placeholder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tick duration and player latency on the 1.20.5 - 1.21.x line, where the server
 * exposes a smoothed tick time and latency moved onto the connection.
 */
public final class ServerMetrics {
	private ServerMetrics() {
	}

	/**
	 * The server's mean tick duration.
	 *
	 * @param server the running server
	 * @return the duration in milliseconds, or 0 when the server has not ticked yet
	 */
	public static double tickDurationMillis(MinecraftServer server) {
		return server.getCurrentSmoothedTickTime();
	}

	/**
	 * A player's measured round-trip time.
	 *
	 * @param player the player to read
	 * @return the latency in milliseconds
	 */
	public static int pingMillis(ServerPlayer player) {
		return player.connection.latency();
	}
}
