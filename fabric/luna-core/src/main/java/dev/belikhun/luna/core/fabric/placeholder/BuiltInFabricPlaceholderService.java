package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.mc.placeholder.LunaPlaceholderExtension;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import com.sun.management.OperatingSystemMXBean;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.heartbeat.SparkMetrics;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;
import dev.belikhun.luna.core.api.placeholder.PlaceholderEscaping;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRoute;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRouting;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.fabric.compat.Guarded;
import dev.belikhun.luna.core.fabric.compat.WorldFacts;
import dev.belikhun.luna.core.fabric.heartbeat.TickRateMonitor;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/**
 * The placeholder values a Fabric backend publishes, and the support surface its
 * providers resolve through.
 *
 * The NeoForge service this is modelled on reaches most server statistics by
 * reflection, looking methods up by name. That cannot work here: Fabric remaps
 * the game to intermediary at runtime, so {@code getMethod("getAverageTickTime")}
 * finds nothing on a production server while a compiled call site is remapped
 * along with the mod. Every reading below is therefore a real call, with
 * {@link Guarded} where a future version is allowed to take one away and
 * {@link WorldFacts} where the two supported game lines disagree outright.
 *
 * Tick rate is the one statistic not read from the game at all: the accessor was
 * renamed twice inside the supported range, so {@link TickRateMonitor} counts the
 * ticks luna is already being told about instead.
 */
public final class BuiltInFabricPlaceholderService implements PlaceholderService {
	static final int DEFAULT_BAR_WIDTH = 25;
	static final int MIN_BAR_WIDTH = 1;
	static final int MAX_BAR_WIDTH = 120;
	static final String DEFAULT_COLOR = "#F1FF68";
	static final DateTimeFormatter WORLD_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
	static final Pattern WORLD_WEATHER_PATTERN = Pattern.compile("^world_(.+)_(weather|weathericon|weathercolor|weatherduration)$", Pattern.CASE_INSENSITIVE);
	static final Pattern PLAYER_STATUS_PATTERN = Pattern.compile("^player_status(?:_(.+))?$", Pattern.CASE_INSENSITIVE);
	static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("^stripcolor_(legacy|mm)_(.+)$", Pattern.CASE_INSENSITIVE);
	static final Pattern MM2L_PATTERN = Pattern.compile("^mm2l_(.+)$", Pattern.CASE_INSENSITIVE);
	static final Pattern BRACKET_PATTERN = Pattern.compile("\\{([^{}]+)}");

	private static final long TICKS_PER_DAY = 24000L;
	private static final int MINUTES_PER_DAY = 1440;

	/** Minecraft's day starts at 06:00, not midnight. */
	private static final int DAY_START_MINUTES = 360;

	private final LunaLogger logger;
	private final MinecraftServer server;
	private final TickRateMonitor tickRate;
	private final BackendIdentity backendIdentity;
	private final List<FabricPlaceholderProvider> placeholderProviders;
	private final Map<String, List<FabricPlaceholderProvider>> placeholderProvidersByNamespace;
	private final Map<String, LunaPlaceholderExtension> extensionsByNamespace;
	private final List<LunaPlaceholderExtension> extensions;
	private volatile SharedSnapshot latestSharedSnapshot;

	/**
	 * The service a backend starts with.
	 *
	 * Provider order is what decides a contested identifier: the permission
	 * providers own their own namespaces, and the imported provider is asked
	 * before the built-in one for {@code luna_*} because its keys carry arguments
	 * the built-in switch would never match anyway.
	 */
	public static BuiltInFabricPlaceholderService createDefault(
		LunaLogger logger,
		MinecraftServer server,
		TickRateMonitor tickRate,
		BackendIdentity backendIdentity,
		PermissionService permissionService
	) {
		return new BuiltInFabricPlaceholderService(
			logger,
			server,
			tickRate,
			backendIdentity,
			List.of(
				new PermissionPlaceholderProvider(permissionService),
				new SparkPlaceholderProvider(),
				new ImportedPlaceholderProvider(),
				new BuiltinPlaceholderProvider()
			)
		);
	}

	BuiltInFabricPlaceholderService(
		LunaLogger logger,
		MinecraftServer server,
		TickRateMonitor tickRate,
		BackendIdentity backendIdentity,
		List<FabricPlaceholderProvider> placeholderProviders
	) {
		this.logger = Objects.requireNonNull(logger, "logger").scope("Placeholders");
		this.server = Objects.requireNonNull(server, "server");
		this.tickRate = Objects.requireNonNull(tickRate, "tickRate");
		this.backendIdentity = Objects.requireNonNull(backendIdentity, "backendIdentity");
		this.placeholderProviders = List.copyOf(Objects.requireNonNull(placeholderProviders, "placeholderProviders"));
		this.placeholderProvidersByNamespace = PlaceholderRouting.indexProvidersByNamespace(this.placeholderProviders);
		this.extensionsByNamespace = new ConcurrentHashMap<>();
		this.extensions = new CopyOnWriteArrayList<>();
	}

	@Override
	public void registerExtension(LunaPlaceholderExtension extension) {
		if (extension == null) {
			return;
		}

		for (String namespace : extension.namespaces()) {
			if (namespace != null && !namespace.isBlank()) {
				extensionsByNamespace.put(namespace.trim().toLowerCase(Locale.ROOT), extension);
			}
		}

		extensions.add(extension);
	}

	@Override
	public void refreshSharedSnapshot() {
		latestSharedSnapshot = sharedSnapshot();
	}

	@Override
	public Map<String, String> snapshot(ServerPlayer player, Collection<String> requestedIdentifiers) {
		if (player == null) {
			return Map.of();
		}

		Map<String, String> values = new LinkedHashMap<>();
		PlaceholderSnapshot currentSnapshot = currentSnapshot(player, cachedSharedSnapshot());

		for (FabricPlaceholderProvider provider : placeholderProviders) {
			provider.contributeSnapshot(this, player, currentSnapshot, values);
		}

		for (LunaPlaceholderExtension extension : extensions) {
			extension.contributeSnapshot(player, values);
		}

		putRequestedValues(values, player, requestedIdentifiers, currentSnapshot);

		return Map.copyOf(values);
	}

	@Override
	public String resolvePlaceholder(ServerPlayer player, String identifier) {
		// a relational placeholder needs two players; whoever asked has the other
		// one and must resolve it itself
		if (player == null || identifier == null || identifier.isBlank() || identifier.startsWith("%rel_")) {
			return null;
		}

		return resolveRequestedValue(player, identifier, currentSnapshot(player, cachedSharedSnapshot()));
	}

	String resolveRequestedValue(ServerPlayer player, String identifier, PlaceholderSnapshot snapshot) {
		String fromExtension = resolveFromExtension(player, identifier);

		if (fromExtension != null) {
			return fromExtension;
		}

		PlaceholderRoute<FabricPlaceholderProvider> route = PlaceholderRouting.resolve(identifier, placeholderProvidersByNamespace);

		if (route == null) {
			return null;
		}

		for (FabricPlaceholderProvider provider : route.providers()) {
			String value = provider.resolve(
				this,
				player,
				route.rawNamespace(),
				route.normalizedNamespace(),
				route.rawParams(),
				route.normalizedParams(),
				snapshot
			);

			if (value != null) {
				return route.safeVariant() ? escapePlaceholderPercents(value) : value;
			}
		}

		return null;
	}

	/**
	 * Ask the extensions, splitting the identifier on its first underscore.
	 *
	 * That is the same split PlaceholderAPI makes on Paper, which is what keeps
	 * one proxy-side template - {@code %lunavault_balance%} - rendering on either
	 * platform.
	 */
	private String resolveFromExtension(ServerPlayer player, String identifier) {
		if (extensionsByNamespace.isEmpty()) {
			return null;
		}

		String unwrapped = PlaceholderRouting.unwrapIdentifier(identifier);

		if (unwrapped == null || unwrapped.isBlank()) {
			return null;
		}

		int separator = unwrapped.indexOf('_');

		if (separator <= 0 || separator >= unwrapped.length() - 1) {
			return null;
		}

		String namespace = unwrapped.substring(0, separator).toLowerCase(Locale.ROOT);
		LunaPlaceholderExtension extension = extensionsByNamespace.get(namespace);

		if (extension == null) {
			return null;
		}

		return extension.resolve(player, namespace, unwrapped.substring(separator + 1).toLowerCase(Locale.ROOT));
	}

	private void putRequestedValues(
		Map<String, String> values,
		ServerPlayer player,
		Collection<String> requestedIdentifiers,
		PlaceholderSnapshot snapshot
	) {
		if (requestedIdentifiers == null || requestedIdentifiers.isEmpty()) {
			return;
		}

		for (String requestedIdentifier : requestedIdentifiers) {
			if (requestedIdentifier == null || requestedIdentifier.isBlank() || values.containsKey(requestedIdentifier)) {
				continue;
			}

			String resolvedValue = resolveRequestedValue(player, requestedIdentifier, snapshot);

			if (resolvedValue != null) {
				values.put(requestedIdentifier, resolvedValue);
			}
		}
	}

	private SharedSnapshot sharedSnapshot() {
		SparkMetrics.Snapshot sparkMetrics = SparkMetrics.collect(
			logger,
			tickRate::tps,
			this::systemCpuPercent,
			this::processCpuPercent
		);

		return new SharedSnapshot(
			sparkMetrics.tps(),
			tickDurationMillis(sparkMetrics.tps()),
			sparkMetrics.sparkTickDuration10Sec(),
			currentUptimeMillis(),
			sparkMetrics.systemCpuUsagePercent(),
			sparkMetrics.processCpuUsagePercent(),
			currentRamUsedBytes(),
			currentRamMaxBytes(),
			countEntities(false),
			countEntities(true),
			countLoadedChunks()
		);
	}

	private PlaceholderSnapshot currentSnapshot(ServerPlayer player, SharedSnapshot sharedSnapshot) {
		return new PlaceholderSnapshot(
			sharedSnapshot.currentTps(),
			sharedSnapshot.currentTickDurationMillis(),
			sharedSnapshot.sparkTickDuration10Sec(),
			playerPingMillis(player),
			sharedSnapshot.uptimeMillis(),
			sharedSnapshot.systemCpuPercent(),
			sharedSnapshot.processCpuPercent(),
			sharedSnapshot.ramUsedBytes(),
			sharedSnapshot.ramMaxBytes(),
			sharedSnapshot.totalEntities(),
			sharedSnapshot.totalLivingEntities(),
			sharedSnapshot.totalChunks()
		);
	}

	private SharedSnapshot cachedSharedSnapshot() {
		SharedSnapshot snapshot = latestSharedSnapshot;

		if (snapshot != null) {
			return snapshot;
		}

		// nobody has refreshed yet; sample once so the first lookup is not blank
		snapshot = sharedSnapshot();
		latestSharedSnapshot = snapshot;

		return snapshot;
	}

	String formatServerTime(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			return "";
		}

		try {
			return DateTimeFormatter.ofPattern(pattern, Locale.US).format(LocalDateTime.now());
		} catch (IllegalArgumentException | DateTimeException exception) {
			logger.debug("Bỏ qua server_time pattern không hợp lệ: " + pattern + " (" + exception.getMessage() + ")");
			return "";
		}
	}

	String currentBiomeName(ServerPlayer player) {
		return Guarded.value(() -> WorldFacts.biomePath(player), "unknown");
	}

	String currentWorldName(ServerPlayer player) {
		String dimensionId = WorldFacts.dimensionId(WorldFacts.levelOf(player));

		return dimensionId.isEmpty() ? "unknown" : dimensionId;
	}

	String currentWorldTime(ServerPlayer player) {
		ServerLevel level = WorldFacts.levelOf(player);

		if (level == null) {
			return "unknown";
		}

		long dayTicks = Math.floorMod(WorldFacts.dayTimeTicks(level), TICKS_PER_DAY);
		int totalMinutes = (int) ((dayTicks * MINUTES_PER_DAY) / TICKS_PER_DAY);

		totalMinutes = Math.floorMod(totalMinutes + DAY_START_MINUTES, MINUTES_PER_DAY);

		return WORLD_TIME_FORMATTER.format(LocalTime.of(totalMinutes / 60, totalMinutes % 60));
	}

	/**
	 * The level a placeholder named by its dimension, e.g. {@code minecraft:the_nether}
	 * or just {@code the_nether}.
	 *
	 * Unlike the NeoForge service this does not also match a level's save name:
	 * that name is the same on every level of a server, so matching it could only
	 * ever return whichever one came first.
	 */
	ServerLevel findLevel(String worldName) {
		if (worldName == null || worldName.isBlank()) {
			return null;
		}

		String normalized = worldName.trim().toLowerCase(Locale.ROOT);

		for (ServerLevel level : server.getAllLevels()) {
			if (normalized.equals(WorldFacts.dimensionPath(level).toLowerCase(Locale.ROOT))
				|| normalized.equals(WorldFacts.dimensionId(level).toLowerCase(Locale.ROOT))) {
				return level;
			}
		}

		return null;
	}

	long currentWeatherDurationTicks(ServerLevel level, boolean raining, boolean thundering) {
		return Guarded.value(() -> WorldFacts.weatherDurationTicks(level, raining, thundering), 0L);
	}

	WorldKind toWorldKind(ServerLevel level) {
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

	String currentServerInfoName() {
		BackendMetadata metadata = backendIdentity.current();

		if (metadata != null) {
			BackendMetadata sanitized = metadata.sanitize();

			if (sanitized.serverName() != null && !sanitized.serverName().isBlank()) {
				return sanitized.serverName();
			}
		}

		return localServerName();
	}

	String localServerName() {
		return backendIdentity.nameOr("backend");
	}

	MinecraftServer server() {
		return server;
	}

	int onlinePlayers() {
		return PlayerLookup.all(server).size();
	}

	int maxPlayers() {
		return Guarded.intValue(server::getMaxPlayers, 0);
	}

	boolean whitelistEnforced() {
		return Guarded.booleanValue(server::isEnforceWhitelist, false);
	}

	String serverVersion() {
		return Guarded.value(server::getServerVersion, "");
	}

	String resolveCurrentBar(String key, String baseKey, IntFunction<String> renderer) {
		Integer width = parseCurrentWidth(key, baseKey);

		if (width == null) {
			return null;
		}

		return renderer.apply(width);
	}

	String resolveExact(String key, String exactKey, Supplier<String> renderer) {
		if (!key.equals(exactKey)) {
			return null;
		}

		return renderer.get();
	}

	/** The width suffix on a bar placeholder, e.g. {@code tps_bar_40}. */
	Integer parseCurrentWidth(String key, String baseKey) {
		if (key.equals(baseKey)) {
			return DEFAULT_BAR_WIDTH;
		}

		String prefix = baseKey + "_";

		if (!key.startsWith(prefix)) {
			return null;
		}

		String widthRaw = key.substring(prefix.length()).trim();

		if (widthRaw.isEmpty()) {
			return DEFAULT_BAR_WIDTH;
		}

		try {
			return clampWidth(Integer.parseInt(widthRaw));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	/** Publish a value under its own name and under the {@code luna_} alias. */
	void putCore(Map<String, String> values, String key, String value) {
		String normalized = safe(value);

		values.put(key, normalized);
		values.put("luna_" + key, normalized);
	}

	void putLunaAlias(Map<String, String> values, String key, String value) {
		values.put("luna_" + key, safe(value));
	}

	String buildBar(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).render();
	}

	String buildBarOnly(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).renderBar();
	}

	String buildValueOnly(LunaProgressBar bar) {
		return bar.renderValue();
	}

	int clampWidth(int width) {
		return Math.max(MIN_BAR_WIDTH, Math.min(MAX_BAR_WIDTH, width));
	}

	private double tickDurationMillis(double currentTps) {
		if (currentTps > 0D) {
			return Math.max(0D, 1000D / currentTps);
		}

		return 50D;
	}

	private int playerPingMillis(ServerPlayer player) {
		if (player == null) {
			return 0;
		}

		return Math.max(0, Guarded.intValue(() -> player.connection.latency(), 0));
	}

	private long currentUptimeMillis() {
		return Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime());
	}

	private long currentRamUsedBytes() {
		Runtime runtime = Runtime.getRuntime();

		return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
	}

	private long currentRamMaxBytes() {
		return Math.max(1L, Runtime.getRuntime().maxMemory());
	}

	private int countEntities(boolean livingOnly) {
		int total = 0;

		for (ServerLevel level : server.getAllLevels()) {
			for (var entity : level.getAllEntities()) {
				if (!livingOnly || entity instanceof LivingEntity) {
					total++;
				}
			}
		}

		return Math.max(0, total);
	}

	private int countLoadedChunks() {
		int total = 0;

		for (ServerLevel level : server.getAllLevels()) {
			total += Guarded.intValue(() -> level.getChunkSource().getLoadedChunksCount(), 0);
		}

		return Math.max(0, total);
	}

	private double systemCpuPercent() {
		return cpuPercent(OperatingSystemMXBean::getCpuLoad);
	}

	private double processCpuPercent() {
		return cpuPercent(OperatingSystemMXBean::getProcessCpuLoad);
	}

	private double cpuPercent(ToDoubleFunction<OperatingSystemMXBean> extractor) {
		try {
			java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();

			if (bean instanceof OperatingSystemMXBean operatingSystemBean) {
				double load = extractor.applyAsDouble(operatingSystemBean);

				if (!Double.isNaN(load) && !Double.isInfinite(load) && load >= 0D) {
					return Math.max(0D, Math.min(100D, load * 100D));
				}
			}
		} catch (Throwable throwable) {
			logger.debug("Không thể đọc CPU load hiện tại: " + throwable.getMessage());
		}

		return 0D;
	}

	String formatPercent(double value) {
		return String.format(Locale.US, "%.1f%%", Math.max(0D, value));
	}

	String formatTps(double value) {
		return String.format(Locale.US, "%.2f", Math.max(0D, value));
	}

	String formatSparkTickDuration(PlaceholderSnapshot snapshot) {
		String sparkValue = safe(snapshot.sparkTickDuration10Sec());

		if (!sparkValue.isBlank()) {
			return sparkValue;
		}

		// spark reports four windows; without it every window is the same reading
		String fallback = formatOneDecimal(snapshot.currentTickDurationMillis());

		return fallback + "/" + fallback + "/" + fallback + "/" + fallback;
	}

	String formatDecimal(double value) {
		String text = String.format(Locale.US, "%.2f", Math.max(0D, value));

		while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
			text = text.substring(0, text.length() - 1);
		}

		return text;
	}

	String formatOneDecimal(double value) {
		return String.format(Locale.US, "%.1f", Math.max(0D, value));
	}

	/**
	 * A value going into a `_safe` placeholder cannot carry percent signs: the
	 * caller is about to run another placeholder pass over the result, and a
	 * value containing them would be read as an identifier.
	 */
	String escapePlaceholderPercents(String value) {
		return PlaceholderEscaping.escapePercents(value);
	}

	String safe(String value) {
		return value == null ? "" : value;
	}

	private record SharedSnapshot(
		double currentTps,
		double currentTickDurationMillis,
		String sparkTickDuration10Sec,
		long uptimeMillis,
		double systemCpuPercent,
		double processCpuPercent,
		long ramUsedBytes,
		long ramMaxBytes,
		int totalEntities,
		int totalLivingEntities,
		int totalChunks
	) {
	}
}
