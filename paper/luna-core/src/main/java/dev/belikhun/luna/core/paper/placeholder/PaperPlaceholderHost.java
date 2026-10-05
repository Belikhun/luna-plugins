package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.heartbeat.SparkMetrics;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;
import dev.belikhun.luna.core.api.placeholder.PlaceholderGrammar;
import dev.belikhun.luna.core.api.placeholder.PlaceholderHost;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRef;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSyntax;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.core.api.placeholder.ServerSample;
import dev.belikhun.luna.core.api.placeholder.WorldWeather;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Paper's view of the server and its players, sampled on the main thread.
 *
 * Players are {@link OfflinePlayer} because that is what PlaceholderAPI hands
 * an expansion; an offline one simply has no sample and resolves to the
 * absent-viewer values.
 */
public final class PaperPlaceholderHost implements PlaceholderHost<OfflinePlayer> {
	private final JavaPlugin plugin;
	private final BackendStatusView statusView;
	private final ConfigStore configStore;
	private final LunaLogger logger;
	private final LuckPermsService permissions;
	private final boolean placeholderApiPresent;

	public PaperPlaceholderHost(JavaPlugin plugin, BackendStatusView statusView, ConfigStore configStore, LunaLogger logger) {
		this.plugin = plugin;
		this.statusView = statusView;
		this.configStore = configStore;
		this.logger = logger.scope("PlaceholderHost");
		this.permissions = Bukkit.getPluginManager().isPluginEnabled("LuckPerms") ? new LuckPermsService() : null;
		this.placeholderApiPresent = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
	}

	@Override
	public UUID idOf(OfflinePlayer player) {
		return player.getUniqueId();
	}

	@Override
	public String nameOf(OfflinePlayer player) {
		String name = player.getName();
		return name == null ? "" : name;
	}

	@Override
	public ServerSample sampleServer() {
		OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		SparkMetrics.Snapshot spark = SparkMetrics.collect(
			logger,
			() -> Bukkit.getTPS().length > 0 ? Math.max(0D, Bukkit.getTPS()[0]) : 20D,
			() -> cpuPercent(os, true),
			() -> cpuPercent(os, false)
		);
		Optional<BackendServerStatus> status = currentStatus();
		Optional<BackendMetadata> metadata = statusView.currentBackendMetadata();

		int entities = 0;
		int living = 0;
		int chunks = 0;
		List<WorldWeather> weather = new ArrayList<>();

		for (World world : Bukkit.getWorlds()) {
			entities += world.getEntityCount();
			chunks += world.getLoadedChunks().length;

			for (org.bukkit.entity.Entity entity : world.getEntities()) {
				if (entity instanceof LivingEntity) {
					living++;
				}
			}

			weather.add(new WorldWeather(world.getName(), world.hasStorm(), world.isThundering(), Math.max(0L, world.getWeatherDuration()), Math.floorMod(world.getTime(), 24_000L)));
		}

		long runtimeUptime = Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime());
		long heartbeatUptime = status.map(BackendServerStatus::stats).map(stats -> Math.max(0L, stats.uptimeMillis())).orElse(0L);
		Runtime runtime = Runtime.getRuntime();

		return new ServerSample(
			backendName(metadata),
			metadata.map(BackendMetadata::serverName).filter(value -> !value.isBlank()).orElse(backendName(metadata)),
			metadata.map(BackendMetadata::displayName).filter(value -> !value.isBlank())
				.orElseGet(() -> status.map(BackendServerStatus::serverDisplay).orElse(null)),
			metadata.map(BackendMetadata::accentColor).filter(value -> !value.isBlank())
				.orElseGet(() -> status.map(BackendServerStatus::serverAccentColor).orElse(null)),
			Bukkit.getVersion(),
			Bukkit.getOnlinePlayers().size(),
			Bukkit.getMaxPlayers(),
			Bukkit.hasWhitelist(),
			spark.tps(),
			Math.max(0D, Bukkit.getAverageTickTime()),
			Math.max(runtimeUptime, heartbeatUptime),
			spark.systemCpuUsagePercent(),
			spark.processCpuUsagePercent(),
			Math.max(0L, runtime.totalMemory() - runtime.freeMemory()),
			Math.max(0L, runtime.maxMemory()),
			entities,
			living,
			chunks,
			status.map(BackendServerStatus::stats).map(stats -> Math.max(0L, stats.heartbeatLatencyMillis())).orElse(0L),
			ServerSample.indexWorlds(weather),
			System.currentTimeMillis()
		);
	}

	@Override
	public PlayerSample samplePlayer(OfflinePlayer offlinePlayer) {
		Player player = offlinePlayer.getPlayer();

		if (player == null) {
			return null;
		}

		PlayerSample.Builder builder = PlayerSample.builder(player.getUniqueId(), player.getName());
		World world = player.getWorld();
		Location location = player.getLocation();

		builder.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(player.displayName()));
		builder.ping(player.getPing());
		builder.world(world.getName(), toWorldKind(world.getEnvironment()), world.getKey().asString());
		builder.position(location.getBlockX(), location.getBlockY(), location.getBlockZ());
		builder.health(player.getHealth(), player.getMaxHealth());
		builder.level(player.getLevel());
		builder.food(player.getFoodLevel());
		builder.gameMode(player.getGameMode().name().toLowerCase(Locale.ROOT));
		builder.biome(world.getBiome(location).getKey().asString());
		builder.voiceChat(SimpleVoiceChatCompat.playerStatus(player.getUniqueId()), SimpleVoiceChatCompat.playerGroup(player.getUniqueId()));

		if (permissions != null && permissions.isAvailable()) {
			UUID id = player.getUniqueId();
			builder.permissions(
				permissions.getPlayerPrefix(id),
				permissions.getPlayerSuffix(id),
				permissions.getGroupName(id),
				permissions.getGroupDisplayName(id)
			);
		}

		return builder.build();
	}

	@Override
	public String foreign(OfflinePlayer player, PlaceholderRef ref) {
		if (!placeholderApiPresent) {
			return null;
		}

		String text = PlaceholderGrammar.format(ref.withSafe(false), PlaceholderSyntax.PERCENT);
		String resolved = PlaceholderAPI.setPlaceholders(player, text);

		if (resolved == null || resolved.equals(text)) {
			return null;
		}

		return resolved;
	}

	private String backendName(Optional<BackendMetadata> metadata) {
		if (metadata.isPresent() && metadata.get().name() != null && !metadata.get().name().isBlank()) {
			return metadata.get().name();
		}

		String configured = configStore.get("heartbeat.serverName").asString("");

		if (configured != null && !configured.isBlank()) {
			return configured.trim().toLowerCase(Locale.ROOT);
		}

		String host = plugin.getServer().getIp();

		if (host == null || host.isBlank()) {
			host = "127.0.0.1";
		}

		return (host + ":" + Bukkit.getPort()).toLowerCase(Locale.ROOT);
	}

	private Optional<BackendServerStatus> currentStatus() {
		Optional<BackendMetadata> metadata = statusView.currentBackendMetadata();

		if (metadata.isPresent()) {
			Optional<BackendServerStatus> status = statusView.status(metadata.get().name());

			if (status.isPresent()) {
				return status;
			}
		}

		int port = plugin.getServer().getPort();

		for (BackendServerStatus status : statusView.snapshot().values()) {
			if (status != null && status.stats() != null && status.stats().serverPort() == port) {
				return Optional.of(status);
			}
		}

		return Optional.empty();
	}

	private static WorldKind toWorldKind(World.Environment environment) {
		return switch (environment) {
			case NORMAL -> WorldKind.NORMAL;
			case NETHER -> WorldKind.NETHER;
			case THE_END -> WorldKind.END;
			default -> WorldKind.CUSTOM;
		};
	}

	private static double cpuPercent(OperatingSystemMXBean os, boolean system) {
		if (!(os instanceof com.sun.management.OperatingSystemMXBean sun)) {
			return 0D;
		}

		double raw = system ? sun.getCpuLoad() : sun.getProcessCpuLoad();

		if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0D) {
			return 0D;
		}

		return Math.max(0D, Math.min(100D, raw * 100D));
	}
}
