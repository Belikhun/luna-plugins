package dev.belikhun.luna.core.mc.placeholder;

import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.heartbeat.SparkMetrics;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;
import dev.belikhun.luna.core.api.placeholder.PlaceholderHost;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.core.api.placeholder.ServerSample;
import dev.belikhun.luna.core.api.placeholder.WorldWeather;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The mod-loader view of the server and its players, shared by Fabric, NeoForge
 * and Forge. Version-specific reads go through {@link GameReads}.
 */
public final class MinecraftPlaceholderHost implements PlaceholderHost<ServerPlayer> {
	private final MinecraftServer server;
	private final BackendIdentity identity;
	private final BackendStatusView statusView;
	private final PermissionService permissions;
	private final GameReads reads;
	private final LunaLogger logger;

	public MinecraftPlaceholderHost(
		MinecraftServer server,
		BackendIdentity identity,
		BackendStatusView statusView,
		PermissionService permissions,
		GameReads reads,
		LunaLogger logger
	) {
		this.server = server;
		this.identity = identity;
		this.statusView = statusView;
		this.permissions = permissions;
		this.reads = reads;
		this.logger = logger.scope("PlaceholderHost");
		// spark's tickduration spread, from the server's own tick ring when spark is absent
		// or too old to answer (forge 1.20.1 ships spark 1.10.53, which crashes on Java 25).
		SparkMetrics.tickTimesFallback(() -> reads.tickTimesNanos(server));
	}

	@Override
	public UUID idOf(ServerPlayer player) {
		return player.getUUID();
	}

	@Override
	public String nameOf(ServerPlayer player) {
		return player.getName().getString();
	}

	@Override
	public ServerSample sampleServer() {
		SparkMetrics.Snapshot spark = SparkMetrics.collect(
			logger,
			() -> reads.tps(server),
			() -> cpu(true),
			() -> cpu(false)
		);
		int entities = 0;
		int living = 0;
		int chunks = 0;
		List<WorldWeather> weather = new ArrayList<>();

		for (ServerLevel level : server.getAllLevels()) {
			for (var entity : level.getAllEntities()) {
				entities++;

				if (entity instanceof LivingEntity) {
					living++;
				}
			}

			chunks += Math.max(0, level.getChunkSource().getLoadedChunksCount());
			boolean raining = level.isRaining();
			boolean thundering = level.isThundering();
			long duration = reads.weatherDurationTicks(level, raining, thundering);
			long dayTime = reads.dayTimeTicks(level);

			// a world answers to its path and to its full id
			weather.add(new WorldWeather(reads.dimensionPath(level), raining, thundering, duration, dayTime));
			weather.add(new WorldWeather(reads.dimensionId(level), raining, thundering, duration, dayTime));
		}

		double tickDuration = reads.tickDurationMillis(server);

		if (tickDuration <= 0D && spark.tps() > 0D) {
			tickDuration = 1000D / spark.tps();
		}

		BackendMetadata metadata = identity.current() == null ? null : identity.current().sanitize();
		Optional<BackendServerStatus> status = currentStatus(metadata);
		Runtime runtime = Runtime.getRuntime();
		String backendName = identity.nameOr("backend");

		return new ServerSample(
			backendName,
			metadata == null || metadata.serverName().isBlank() ? backendName : metadata.serverName(),
			metadata == null || metadata.displayName().isBlank()
				? status.map(BackendServerStatus::serverDisplay).orElse(null)
				: metadata.displayName(),
			metadata == null || metadata.accentColor().isBlank()
				? status.map(BackendServerStatus::serverAccentColor).orElse(null)
				: metadata.accentColor(),
			server.getServerVersion(),
			server.getPlayerCount(),
			server.getMaxPlayers(),
			server.isEnforceWhitelist(),
			spark.tps(),
			Math.max(0D, tickDuration),
			Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime()),
			spark.systemCpuUsagePercent(),
			spark.processCpuUsagePercent(),
			Math.max(0L, runtime.totalMemory() - runtime.freeMemory()),
			Math.max(1L, runtime.maxMemory()),
			entities,
			living,
			chunks,
			status.map(BackendServerStatus::stats).map(stats -> Math.max(0L, stats.heartbeatLatencyMillis())).orElse(0L),
			ServerSample.indexWorlds(weather),
			System.currentTimeMillis()
		);
	}

	@Override
	public PlayerSample samplePlayer(ServerPlayer player) {
		ServerLevel level = reads.levelOf(player);
		PlayerSample.Builder builder = PlayerSample.builder(player.getUUID(), player.getName().getString());

		builder.displayName(player.getDisplayName().getString());
		builder.ping(reads.pingMillis(player));
		builder.world(reads.dimensionPath(level), toWorldKind(level), reads.dimensionId(level));
		builder.position(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		builder.health(player.getHealth(), player.getMaxHealth());
		builder.level(player.experienceLevel);
		builder.food(player.getFoodData().getFoodLevel());
		builder.biome(reads.biomePath(player));
		builder.voiceChat(SimpleVoiceChatCompat.playerStatus(player.getUUID()), SimpleVoiceChatCompat.playerGroup(player.getUUID()));

		if (permissions != null && permissions.isAvailable()) {
			UUID id = player.getUUID();
			builder.permissions(
				permissions.getPlayerPrefix(id),
				permissions.getPlayerSuffix(id),
				permissions.getGroupName(id),
				permissions.getGroupDisplayName(id)
			);
		}

		return builder.build();
	}

	/** Overworld, nether and end by their dimension keys; anything else is custom. */
	public static WorldKind toWorldKind(ServerLevel level) {
		if (level == null) {
			return WorldKind.CUSTOM;
		}

		if (Level.OVERWORLD.equals(level.dimension())) {
			return WorldKind.NORMAL;
		}

		if (Level.NETHER.equals(level.dimension())) {
			return WorldKind.NETHER;
		}

		if (Level.END.equals(level.dimension())) {
			return WorldKind.END;
		}

		return WorldKind.CUSTOM;
	}

	private Optional<BackendServerStatus> currentStatus(BackendMetadata metadata) {
		if (statusView == null || metadata == null || metadata.name().isBlank()) {
			return Optional.empty();
		}

		return statusView.status(metadata.name());
	}

	private static double cpu(boolean system) {
		java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();

		if (!(bean instanceof com.sun.management.OperatingSystemMXBean sun)) {
			return 0D;
		}

		double load = system ? sun.getCpuLoad() : sun.getProcessCpuLoad();

		if (Double.isNaN(load) || Double.isInfinite(load) || load < 0D) {
			return 0D;
		}

		return Math.max(0D, Math.min(100D, load * 100D));
	}
}
