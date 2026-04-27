package dev.belikhun.luna.core.neoforge.placeholder;

import com.sun.management.OperatingSystemMXBean;
import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeSparkMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuiltInNeoForgePlaceholderService implements NeoForgePlaceholderService {
	static final int DEFAULT_BAR_WIDTH = 25;
	static final int MIN_BAR_WIDTH = 1;
	static final int MAX_BAR_WIDTH = 120;
	static final String DEFAULT_COLOR = "#F1FF68";
	static final String LUNA_PREFIX = "luna_";
	static final String SAFE_SUFFIX = "_safe";
	static final String SERVER_TIME_PREFIX = "server_time_";
	static final String SPARK_PREFIX = "spark_";
	static final String SPARK_TICK_DURATION_10S = "spark_tickduration_10s";
	static final DateTimeFormatter WORLD_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
	static final Pattern WORLD_WEATHER_PATTERN = Pattern.compile("^world_(.+)_(weather|weathericon|weathercolor|weatherduration)$", Pattern.CASE_INSENSITIVE);
	static final Pattern PLAYER_STATUS_PATTERN = Pattern.compile("^player_status(?:_(.+))?$", Pattern.CASE_INSENSITIVE);
	static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("^stripcolor_(legacy|mm)_(.+)$", Pattern.CASE_INSENSITIVE);
	static final Pattern MM2L_PATTERN = Pattern.compile("^mm2l_(.+)$", Pattern.CASE_INSENSITIVE);
	static final Pattern BRACKET_PATTERN = Pattern.compile("\\{([^{}]+)}");

	private final LunaLogger logger;
	private final MinecraftServer server;
	private final String localServerName;
	private final Supplier<BackendMetadata> currentBackendMetadataSupplier;
	private final List<NeoForgePlaceholderProvider> placeholderProviders;
	private volatile SharedSnapshot latestSharedSnapshot;

	public BuiltInNeoForgePlaceholderService(
		LunaLogger logger,
		MinecraftServer server,
		String localServerName,
		List<NeoForgePlaceholderProvider> placeholderProviders,
		Supplier<BackendMetadata> currentBackendMetadataSupplier
	) {
		this.logger = Objects.requireNonNull(logger, "logger").scope("Placeholders");
		this.server = Objects.requireNonNull(server, "server");
		this.localServerName = localServerName == null || localServerName.isBlank() ? "backend" : localServerName;
		this.currentBackendMetadataSupplier = currentBackendMetadataSupplier == null ? () -> null : currentBackendMetadataSupplier;
		this.placeholderProviders = List.copyOf(Objects.requireNonNull(placeholderProviders, "placeholderProviders"));
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

		return snapshotFor(player, requestedIdentifiers, cachedSharedSnapshot());
	}

	@Override
	public String resolvePlaceholder(ServerPlayer player, String identifier) {
		if (player == null || identifier == null || identifier.isBlank() || identifier.startsWith("%rel_")) {
			return null;
		}

		return resolveRequestedValue(player, identifier, currentSnapshot(player, cachedSharedSnapshot()));
	}

	private Map<String, String> snapshotFor(ServerPlayer player, Collection<String> requestedIdentifiers, SharedSnapshot sharedSnapshot) {
		Map<String, String> values = new LinkedHashMap<>();
		NeoForgePlaceholderSnapshot currentSnapshot = currentSnapshot(player, sharedSnapshot);
		for (NeoForgePlaceholderProvider provider : placeholderProviders) {
			provider.contributeSnapshot(this, player, currentSnapshot, values);
		}

		putRequestedValues(values, player, requestedIdentifiers, currentSnapshot);
		return Map.copyOf(values);
	}

	private SharedSnapshot sharedSnapshot() {
		long uptimeMillis = currentUptimeMillis();
		long ramUsedBytes = currentRamUsedBytes();
		long ramMaxBytes = currentRamMaxBytes();
		NeoForgeSparkMetrics.Snapshot sparkMetrics = NeoForgeSparkMetrics.collect(
			logger,
			this::currentTps,
			this::systemCpuPercent,
			this::processCpuPercent
		);
		return new SharedSnapshot(
			sparkMetrics.tps(),
			currentTickDurationMillis(sparkMetrics.tps()),
			sparkMetrics.sparkTickDuration10Sec(),
			uptimeMillis,
			sparkMetrics.systemCpuUsagePercent(),
			sparkMetrics.processCpuUsagePercent(),
			ramUsedBytes,
			ramMaxBytes,
			countEntities(),
			countLivingEntities(),
			countLoadedChunks()
		);
	}

	private NeoForgePlaceholderSnapshot currentSnapshot(ServerPlayer player, SharedSnapshot sharedSnapshot) {
		return new NeoForgePlaceholderSnapshot(
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

		snapshot = sharedSnapshot();
		latestSharedSnapshot = snapshot;
		return snapshot;
	}

	private void putRequestedValues(Map<String, String> values, ServerPlayer player, Collection<String> requestedIdentifiers, NeoForgePlaceholderSnapshot snapshot) {
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

	private String unwrapIdentifier(String identifier) {
		if (identifier == null) {
			return "";
		}

		String trimmed = identifier.trim();
		if (trimmed.isEmpty()) {
			return "";
		}

		if (trimmed.startsWith("%") && trimmed.endsWith("%") && trimmed.length() >= 3) {
			return trimmed.substring(1, trimmed.length() - 1);
		}

		return trimmed;
	}

	String resolveRequestedValue(ServerPlayer player, String identifier, NeoForgePlaceholderSnapshot snapshot) {
		String rawIdentifier = unwrapIdentifier(identifier);
		if (rawIdentifier.isBlank()) {
			return null;
		}

		String normalizedIdentifier = rawIdentifier.toLowerCase(Locale.ROOT);
		if (normalizedIdentifier.startsWith(LUNA_PREFIX)) {
			return resolveRequestedBridgeValue(player, rawIdentifier, normalizedIdentifier, snapshot);
		}

		return resolveNativePlaceholder(player, rawIdentifier, normalizedIdentifier, snapshot);
	}

	private String resolveRequestedBridgeValue(ServerPlayer player, String rawLookupKey, String normalizedLookupKey, NeoForgePlaceholderSnapshot snapshot) {
		boolean safeVariant = normalizedLookupKey.endsWith(SAFE_SUFFIX) && normalizedLookupKey.length() > SAFE_SUFFIX.length();
		String rawKey = safeVariant
			? rawLookupKey.substring(0, rawLookupKey.length() - SAFE_SUFFIX.length())
			: rawLookupKey;
		String normalizedKey = safeVariant
			? normalizedLookupKey.substring(0, normalizedLookupKey.length() - SAFE_SUFFIX.length())
			: normalizedLookupKey;
		if (!normalizedKey.startsWith(LUNA_PREFIX)) {
			return null;
		}

		String rawKeyWithoutPrefix = rawKey.substring(LUNA_PREFIX.length());
		String normalizedKeyWithoutPrefix = normalizedKey.substring(LUNA_PREFIX.length());
		for (NeoForgePlaceholderProvider provider : placeholderProviders) {
			String value = provider.resolveLunaValue(this, player, rawKeyWithoutPrefix, normalizedKeyWithoutPrefix, snapshot);
			if (value != null) {
				return safeVariant ? escapePlaceholderPercents(value) : value;
			}
		}

		return null;
	}

	private String resolveNativePlaceholder(ServerPlayer player, String rawIdentifier, String normalizedIdentifier, NeoForgePlaceholderSnapshot snapshot) {
		for (NeoForgePlaceholderProvider provider : placeholderProviders) {
			String value = provider.resolveNativeValue(this, player, rawIdentifier, normalizedIdentifier, snapshot);
			if (value != null) {
				return value;
			}
		}

		return null;
	}

	String formatServerTime(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			return "";
		}

		try {
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.US);
			return formatter.format(LocalDateTime.now());
		} catch (IllegalArgumentException | DateTimeException exception) {
			logger.debug("Bỏ qua server_time pattern không hợp lệ: " + pattern + " (" + exception.getMessage() + ")");
			return "";
		}
	}

	String currentBiomeName(ServerPlayer player) {
		BlockPos blockPos = player.blockPosition();
		return player.serverLevel().getBiome(blockPos).unwrapKey()
			.map(key -> key.location().getPath())
			.orElse("unknown");
	}

	String currentWorldName(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		if (level == null || level.dimension() == null || level.dimension().location() == null) {
			return "unknown";
		}

		ResourceLocation location = level.dimension().location();
		return location.toString();
	}

	String currentWorldTime(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		if (level == null) {
			return "unknown";
		}

		long dayTicks = Math.floorMod(level.getDayTime(), 24000L);
		int totalMinutes = (int) ((dayTicks * 1440L) / 24000L);
		totalMinutes = Math.floorMod(totalMinutes + 360, 1440);
		LocalTime worldTime = LocalTime.of(totalMinutes / 60, totalMinutes % 60);
		return WORLD_TIME_FORMATTER.format(worldTime);
	}

	ServerLevel findLevel(String worldName) {
		if (worldName == null || worldName.isBlank()) {
			return null;
		}

		String normalized = worldName.trim().toLowerCase(Locale.ROOT);
		for (ServerLevel level : server.getAllLevels()) {
			ResourceLocation location = level.dimension().location();
			if (location != null) {
				if (normalized.equals(location.getPath().toLowerCase(Locale.ROOT)) || normalized.equals(location.toString().toLowerCase(Locale.ROOT))) {
					return level;
				}
			}

			Object levelData = invokeNoArg(level, "getLevelData");
			String levelName = invokeString(levelData, "getLevelName");
			if (levelName != null && normalized.equals(levelName.trim().toLowerCase(Locale.ROOT))) {
				return level;
			}
		}

		return null;
	}

	long currentWeatherDurationTicks(ServerLevel level, boolean raining, boolean thundering) {
		Object levelData = invokeNoArg(level, "getLevelData");
		if (levelData == null) {
			return 0L;
		}

		if (thundering) {
			Integer thunderTime = invokeInt(levelData, "getThunderTime");
			if (thunderTime != null && thunderTime >= 0) {
				return thunderTime.longValue();
			}
		}

		if (raining) {
			Integer rainTime = invokeInt(levelData, "getRainTime");
			if (rainTime != null && rainTime >= 0) {
				return rainTime.longValue();
			}
		}

		Integer clearWeatherTime = invokeInt(levelData, "getClearWeatherTime");
		return clearWeatherTime == null || clearWeatherTime < 0 ? 0L : clearWeatherTime.longValue();
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
		BackendMetadata metadata = currentBackendMetadataSupplier.get();
		if (metadata != null) {
			BackendMetadata sanitized = metadata.sanitize();
			if (sanitized.serverName() != null && !sanitized.serverName().isBlank()) {
				return sanitized.serverName();
			}
		}
		return localServerName;
	}

	String localServerName() {
		return localServerName;
	}

	MinecraftServer server() {
		return server;
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

	void putCore(Map<String, String> values, String key, String value) {
		String normalized = safe(value);
		values.put(key, normalized);
		values.put("luna_" + key, normalized);
		values.put(key + "_safe", escapePlaceholderPercents(normalized));
		values.put("luna_" + key + "_safe", escapePlaceholderPercents(normalized));
	}

	void putLunaAlias(Map<String, String> values, String key, String value) {
		String normalized = safe(value);
		values.put("luna_" + key, normalized);
		values.put("luna_" + key + "_safe", escapePlaceholderPercents(normalized));
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

	private double currentTickDurationMillis(double currentTps) {
		for (String methodName : new String[] {"getAverageTickTime", "getCurrentSmoothedTickTime", "getTickTime"}) {
			Double averageTickTime = invokeDouble(server, methodName);
			if (averageTickTime != null && averageTickTime > 0D) {
				return averageTickTime;
			}
		}

		Object tickTimes = readField(server, "tickTimes");
		if (tickTimes instanceof long[] values && values.length > 0) {
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
				return Math.max(0D, (total / (double) samples) / 1_000_000D);
			}
		}

		if (currentTps > 0D) {
			return Math.max(0D, 1000D / currentTps);
		}

		return 50D;
	}

	private double currentTps() {
		for (String methodName : new String[] {"getAverageTickTime", "getCurrentSmoothedTickTime", "getTickTime"}) {
			Double averageTickTime = invokeDouble(server, methodName);
			if (averageTickTime == null || averageTickTime <= 0D) {
				continue;
			}

			return Math.max(0D, Math.min(20D, 1000D / averageTickTime));
		}

		Object tickTimes = readField(server, "tickTimes");
		if (tickTimes instanceof long[] values && values.length > 0) {
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
					return Math.max(0D, Math.min(20D, 1000D / averageTickTimeMillis));
				}
			}
		}

		return 20D;
	}

	private int playerPingMillis(ServerPlayer player) {
		if (player == null) {
			return 0;
		}

		for (String methodName : new String[] {"latency", "getLatency", "connectionLatency", "getConnectionLatency"}) {
			Integer directValue = invokeInt(player, methodName);
			if (directValue != null && directValue >= 0) {
				return directValue;
			}
		}

		Object connection = readField(player, "connection");
		if (connection != null) {
			for (String methodName : new String[] {"latency", "getLatency", "connectionLatency", "getConnectionLatency"}) {
				Integer connectionValue = invokeInt(connection, methodName);
				if (connectionValue != null && connectionValue >= 0) {
					return connectionValue;
				}
			}

			for (String fieldName : new String[] {"latency", "connectionLatency"}) {
				Object value = readField(connection, fieldName);
				if (value instanceof Number number) {
					return Math.max(0, number.intValue());
				}
			}
		}

		for (String fieldName : new String[] {"latency", "connectionLatency"}) {
			Object value = readField(player, fieldName);
			if (value instanceof Number number) {
				return Math.max(0, number.intValue());
			}
		}

		return 0;
	}

	private Double invokeDouble(Object target, String methodName) {
		Object value = invokeNoArg(target, methodName);
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		return null;
	}

	private Integer invokeInt(Object target, String methodName) {
		Object value = invokeNoArg(target, methodName);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private String invokeString(Object target, String methodName) {
		Object value = invokeNoArg(target, methodName);
		return value instanceof String stringValue ? stringValue : null;
	}

	private Object invokeNoArg(Object target, String methodName) {
		if (target == null) {
			return null;
		}

		try {
			Method method = target.getClass().getMethod(methodName);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private Object readField(Object target, String fieldName) {
		if (target == null) {
			return null;
		}

		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field.get(target);
			} catch (ReflectiveOperationException ignored) {
				type = type.getSuperclass();
			}
		}

		return null;
	}

	private long currentUptimeMillis() {
		try {
			return Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime());
		} catch (Throwable throwable) {
			logger.debug("Không thể đọc uptime hiện tại: " + throwable.getMessage());
			return 0L;
		}
	}

	private long currentRamUsedBytes() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
	}

	private long currentRamMaxBytes() {
		return Math.max(1L, Runtime.getRuntime().maxMemory());
	}

	private int countEntities() {
		int total = 0;
		for (ServerLevel level : server.getAllLevels()) {
			total += countEntities(level, false);
		}
		return Math.max(0, total);
	}

	private int countLivingEntities() {
		int total = 0;
		for (ServerLevel level : server.getAllLevels()) {
			total += countEntities(level, true);
		}
		return Math.max(0, total);
	}

	private int countLoadedChunks() {
		int total = 0;
		for (ServerLevel level : server.getAllLevels()) {
			Object chunkSource = level.getChunkSource();
			Integer loadedChunks = invokeInt(chunkSource, "getLoadedChunksCount");
			if (loadedChunks != null && loadedChunks >= 0) {
				total += loadedChunks;
				continue;
			}

			Object chunkMap = readField(chunkSource, "chunkMap");
			Integer reflectedSize = invokeInt(chunkMap, "size");
			if (reflectedSize != null && reflectedSize >= 0) {
				total += reflectedSize;
			}
		}
		return Math.max(0, total);
	}

	private int countEntities(ServerLevel level, boolean livingOnly) {
		Iterable<?> entities = allEntities(level);
		if (entities == null) {
			return 0;
		}

		int total = 0;
		for (Object entity : entities) {
			if (!livingOnly || entity instanceof LivingEntity) {
				total++;
			}
		}
		return total;
	}

	private Iterable<?> allEntities(ServerLevel level) {
		Object direct = invokeNoArg(level, "getAllEntities");
		if (direct instanceof Iterable<?> iterable) {
			return iterable;
		}

		Object entityGetter = invokeNoArg(level, "getEntities");
		Object nested = invokeNoArg(entityGetter, "getAll");
		if (nested instanceof Iterable<?> iterable) {
			return iterable;
		}

		return null;
	}

	private double systemCpuPercent() {
		return cpuPercent(OperatingSystemMXBean::getCpuLoad);
	}

	private double processCpuPercent() {
		return cpuPercent(OperatingSystemMXBean::getProcessCpuLoad);
	}

	private double cpuPercent(java.util.function.ToDoubleFunction<OperatingSystemMXBean> extractor) {
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

	String formatSparkTickDuration(NeoForgePlaceholderSnapshot snapshot) {
		String sparkValue = safe(snapshot.sparkTickDuration10Sec());
		if (!sparkValue.isBlank()) {
			return sparkValue;
		}

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

	String escapePlaceholderPercents(String value) {
		return safe(value).replace("%", "%%");
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
