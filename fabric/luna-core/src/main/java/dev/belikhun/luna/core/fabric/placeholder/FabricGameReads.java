package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.fabric.compat.Guarded;
import dev.belikhun.luna.core.fabric.compat.WorldFacts;
import dev.belikhun.luna.core.fabric.heartbeat.TickRateMonitor;
import dev.belikhun.luna.core.mc.placeholder.GameReads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@link GameReads} for Fabric, which spans 1.20 through 26.x and therefore
 * reads the world through {@link WorldFacts} and counts ticks itself.
 */
public final class FabricGameReads implements GameReads {
	private final TickRateMonitor tickRate;

	public FabricGameReads(TickRateMonitor tickRate) {
		this.tickRate = tickRate;
	}

	@Override
	public double tps(MinecraftServer server) {
		return Math.max(0D, Math.min(20D, tickRate.tps()));
	}

	@Override
	public double tickDurationMillis(MinecraftServer server) {
		double tps = tickRate.tps();
		return tps <= 0D ? 0D : 1000D / tps;
	}

	@Override
	public int pingMillis(ServerPlayer player) {
		return Math.max(0, Guarded.intValue(() -> player.connection.latency(), 0));
	}

	@Override
	public ServerLevel levelOf(ServerPlayer player) {
		return WorldFacts.levelOf(player);
	}

	@Override
	public long dayTimeTicks(ServerLevel level) {
		return Guarded.value(() -> WorldFacts.dayTimeTicks(level), 0L);
	}

	@Override
	public long weatherDurationTicks(ServerLevel level, boolean raining, boolean thundering) {
		return Guarded.value(() -> WorldFacts.weatherDurationTicks(level, raining, thundering), 0L);
	}

	@Override
	public String dimensionId(ServerLevel level) {
		return Guarded.value(() -> WorldFacts.dimensionId(level), "");
	}

	@Override
	public String dimensionPath(ServerLevel level) {
		return Guarded.value(() -> WorldFacts.dimensionPath(level), "");
	}

	@Override
	public String biomePath(ServerPlayer player) {
		return Guarded.value(() -> WorldFacts.biomePath(player), "unknown");
	}
}
