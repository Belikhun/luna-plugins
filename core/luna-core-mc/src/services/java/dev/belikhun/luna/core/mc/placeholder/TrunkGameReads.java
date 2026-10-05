package dev.belikhun.luna.core.mc.placeholder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * {@link GameReads} for NeoForge and Forge, where the per-version compat set
 * supplies {@link ServerMetrics} and the rest of the accessors are stable.
 */
public final class TrunkGameReads implements GameReads {
	@Override
	public double tps(MinecraftServer server) {
		double duration = ServerMetrics.tickDurationMillis(server);

		if (duration <= 0D) {
			return 20D;
		}

		return Math.max(0D, Math.min(20D, 1000D / duration));
	}

	@Override
	public double tickDurationMillis(MinecraftServer server) {
		return Math.max(0D, ServerMetrics.tickDurationMillis(server));
	}

	@Override
	public int pingMillis(ServerPlayer player) {
		return Math.max(0, ServerMetrics.pingMillis(player));
	}

	@Override
	public ServerLevel levelOf(ServerPlayer player) {
		return player.serverLevel();
	}

	@Override
	public long dayTimeTicks(ServerLevel level) {
		return Math.floorMod(level.getDayTime(), 24000L);
	}

	@Override
	public long weatherDurationTicks(ServerLevel level, boolean raining, boolean thundering) {
		if (!(level.getLevelData() instanceof ServerLevelData levelData)) {
			return 0L;
		}

		if (thundering) {
			return Math.max(0L, levelData.getThunderTime());
		}

		if (raining) {
			return Math.max(0L, levelData.getRainTime());
		}

		return Math.max(0L, levelData.getClearWeatherTime());
	}

	@Override
	public String dimensionId(ServerLevel level) {
		return level == null || level.dimension().location() == null ? "" : level.dimension().location().toString();
	}

	@Override
	public String dimensionPath(ServerLevel level) {
		return level == null || level.dimension().location() == null ? "" : level.dimension().location().getPath();
	}

	@Override
	public String biomePath(ServerPlayer player) {
		return player.serverLevel().getBiome(player.blockPosition()).unwrapKey()
			.map(key -> key.location().getPath())
			.orElse("unknown");
	}
}
