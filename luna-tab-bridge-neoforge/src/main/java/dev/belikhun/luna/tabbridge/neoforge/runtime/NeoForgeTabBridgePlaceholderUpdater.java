package dev.belikhun.luna.tabbridge.neoforge.runtime;

import com.sun.management.OperatingSystemMXBean;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import dev.belikhun.luna.core.neoforge.heartbeat.NeoForgeSparkMetrics;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class NeoForgeTabBridgePlaceholderUpdater implements AutoCloseable {
	private static final int DEFAULT_BAR_WIDTH = 25;
	private static final int MIN_BAR_WIDTH = 1;
	private static final int MAX_BAR_WIDTH = 120;
	private static final long REFRESH_INTERVAL_MILLIS = 50L;
	private static final String DEFAULT_COLOR = "#F1FF68";
	private static final String LUNA_PREFIX = "luna_";
	private static final String SAFE_SUFFIX = "_safe";
	private static final String SERVER_TIME_PREFIX = "server_time_";
	private static final String SPARK_TICK_DURATION_10S = "spark_tickduration_10s";

	private final LunaLogger logger;
	private final MinecraftServer server;
	private final NeoForgeTabBridgeRuntime runtime;
	private final NeoForgeTabBridgeRelationalPlaceholderSource relationalPlaceholderSource;
	private final String localServerName;
	private final Supplier<BackendMetadata> currentBackendMetadataSupplier;
	private final ScheduledExecutorService refreshExecutor;
	private volatile SharedSnapshot latestSharedSnapshot;
	private volatile boolean closed;

	public NeoForgeTabBridgePlaceholderUpdater(LunaLogger logger, MinecraftServer server, NeoForgeTabBridgeRuntime runtime, NeoForgeTabBridgeRelationalPlaceholderSource relationalPlaceholderSource, String localServerName, Supplier<BackendMetadata> currentBackendMetadataSupplier) {
		this.logger = logger.scope("Placeholders");
		this.server = Objects.requireNonNull(server, "server");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.relationalPlaceholderSource = Objects.requireNonNull(relationalPlaceholderSource, "relationalPlaceholderSource");
		this.localServerName = localServerName == null || localServerName.isBlank() ? "backend" : localServerName;
		this.currentBackendMetadataSupplier = currentBackendMetadataSupplier == null ? () -> null : currentBackendMetadataSupplier;
		this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-tabbridge-neoforge-placeholders");
			thread.setDaemon(true);
			return thread;
		});
		this.refreshExecutor.scheduleAtFixedRate(
			() -> {
				if (closed) {
					return;
				}

				this.server.execute(() -> {
					if (!closed) {
						refreshOnlinePlayers();
					}
				});
			},
			REFRESH_INTERVAL_MILLIS,
			REFRESH_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	public void refreshOnlinePlayers() {
		if (closed) {
			return;
		}

		SharedSnapshot sharedSnapshot = sharedSnapshot();
		latestSharedSnapshot = sharedSnapshot;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			refreshPlayer(player, sharedSnapshot);
		}
	}

	public void refreshPlayer(ServerPlayer player) {
		refreshPlayer(player, cachedSharedSnapshot());
	}

	public String resolvePlaceholder(ServerPlayer player, String identifier) {
		if (closed || player == null || identifier == null || identifier.isBlank() || identifier.startsWith("%rel_")) {
			return null;
		}

		String rawIdentifier = unwrapIdentifier(identifier);
		if (rawIdentifier.isBlank()) {
			return null;
		}

		String normalizedIdentifier = rawIdentifier.toLowerCase(Locale.ROOT);

		return resolveRequestedValue(player, identifier, currentSnapshot(player, cachedSharedSnapshot()));
	}

	private void refreshPlayer(ServerPlayer player, SharedSnapshot sharedSnapshot) {
		if (closed || player == null) {
			return;
		}

		runtime.updatePlayerPlaceholders(player, snapshotFor(player, sharedSnapshot));
		runtime.updatePlayerRelationalPlaceholders(player, relationalPlaceholderSource.resolve(player));
	}

	private Map<String, String> snapshotFor(ServerPlayer player, SharedSnapshot sharedSnapshot) {
		Map<String, String> values = new LinkedHashMap<>();
		int playerPingMillis = playerPingMillis(player);
		putCore(values, "current_server", localServerName);
		putCore(values, "status", server.isEnforceWhitelist() ? "MAINT" : "ONLINE");
		putCore(values, "online", Integer.toString(server.getPlayerCount()));
		putCore(values, "max", Integer.toString(server.getMaxPlayers()));
		putCore(values, "tps", formatTps(sharedSnapshot.currentTps()));
		putCore(values, "player_ping", Integer.toString(playerPingMillis));
		putCore(values, "latency", "0");
		putCore(values, "uptime", Formatters.compactDuration(Duration.ofMillis(sharedSnapshot.uptimeMillis())));
		putCore(values, "uptime_long", Formatters.duration(Duration.ofMillis(sharedSnapshot.uptimeMillis())));
		putCore(values, "uptime_ms", Long.toString(sharedSnapshot.uptimeMillis()));
		putCore(values, "system_cpu", formatPercent(sharedSnapshot.systemCpuPercent()));
		putCore(values, "process_cpu", formatPercent(sharedSnapshot.processCpuPercent()));
		putCore(values, "version", safe(server.getServerVersion()));
		putCore(values, "display", localServerName);
		String currentHostName = currentServerInfoName();
		putCore(values, "server_name", currentHostName);
		putLunaAlias(values, "host_name", currentHostName);
		putCore(values, "color", DEFAULT_COLOR);
		putCore(values, "whitelist", Boolean.toString(server.isEnforceWhitelist()));
		putCore(values, "total_entities", Integer.toString(sharedSnapshot.totalEntities()));
		putCore(values, "total_living_entities", Integer.toString(sharedSnapshot.totalLivingEntities()));
		putCore(values, "total_chunks", Integer.toString(sharedSnapshot.totalChunks()));

		putCore(values, "tps_bar", buildBar(LunaProgressBarPresets.tps("tps", sharedSnapshot.currentTps()), DEFAULT_BAR_WIDTH));
		putCore(values, "player_ping_bar", buildBar(LunaProgressBarPresets.latency("ping", playerPingMillis), DEFAULT_BAR_WIDTH));
		putCore(values, "latency_bar", buildBar(LunaProgressBarPresets.latency("latency", 0D), DEFAULT_BAR_WIDTH));
		putCore(values, "system_cpu_bar", buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", sharedSnapshot.systemCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "process_cpu_bar", buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", sharedSnapshot.processCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "ram_bar", buildBar(LunaProgressBarPresets.ram("ram", sharedSnapshot.ramUsedBytes(), sharedSnapshot.ramMaxBytes()), DEFAULT_BAR_WIDTH));

		putCore(values, "tps_bar_only", buildBarOnly(LunaProgressBarPresets.tps("tps", sharedSnapshot.currentTps()), DEFAULT_BAR_WIDTH));
		putCore(values, "player_ping_bar_only", buildBarOnly(LunaProgressBarPresets.latency("ping", playerPingMillis), DEFAULT_BAR_WIDTH));
		putCore(values, "latency_bar_only", buildBarOnly(LunaProgressBarPresets.latency("latency", 0D), DEFAULT_BAR_WIDTH));
		putCore(values, "system_cpu_bar_only", buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", sharedSnapshot.systemCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "process_cpu_bar_only", buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", sharedSnapshot.processCpuPercent()), DEFAULT_BAR_WIDTH));
		putCore(values, "ram_bar_only", buildBarOnly(LunaProgressBarPresets.ram("ram", sharedSnapshot.ramUsedBytes(), sharedSnapshot.ramMaxBytes()), DEFAULT_BAR_WIDTH));

		putCore(values, "tps_bar_value_only", buildValueOnly(LunaProgressBarPresets.tps("tps", sharedSnapshot.currentTps())));
		putCore(values, "player_ping_bar_value_only", buildValueOnly(LunaProgressBarPresets.latency("ping", playerPingMillis)));
		putCore(values, "latency_bar_value_only", buildValueOnly(LunaProgressBarPresets.latency("latency", 0D)));
		putCore(values, "system_cpu_bar_value_only", buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", sharedSnapshot.systemCpuPercent())));
		putCore(values, "process_cpu_bar_value_only", buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", sharedSnapshot.processCpuPercent())));
		putCore(values, "ram_bar_value_only", buildValueOnly(LunaProgressBarPresets.ram("ram", sharedSnapshot.ramUsedBytes(), sharedSnapshot.ramMaxBytes())));
		putRequestedValues(values, player, runtime.requestedPlaceholderIdentifiers(player.getUUID()), currentSnapshot(player, sharedSnapshot));
		return Map.copyOf(values);
	}

	private CurrentSnapshot currentSnapshot(ServerPlayer player, SharedSnapshot sharedSnapshot) {
		return new CurrentSnapshot(
			sharedSnapshot.currentTps(),
			sharedSnapshot.currentTickDurationMillis(),
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

	private void putRequestedValues(Map<String, String> values, ServerPlayer player, Collection<String> requestedIdentifiers, CurrentSnapshot snapshot) {
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
		if (!trimmed.startsWith("%") || !trimmed.endsWith("%") || trimmed.length() < 3) {
			return "";
		}

		return trimmed.substring(1, trimmed.length() - 1);
	}

	private String resolveRequestedValue(ServerPlayer player, String identifier, CurrentSnapshot snapshot) {
		String rawIdentifier = unwrapIdentifier(identifier);
		if (rawIdentifier.isBlank()) {
			return null;
		}

		String normalizedIdentifier = rawIdentifier.toLowerCase(Locale.ROOT);
		if (normalizedIdentifier.startsWith(LUNA_PREFIX)) {
			return resolveRequestedBridgeValue(normalizedIdentifier, snapshot);
		}

		return resolveNativePlaceholder(player, rawIdentifier, normalizedIdentifier, snapshot);
	}

	private String resolveRequestedBridgeValue(String lookupKey, CurrentSnapshot snapshot) {
		boolean safeVariant = lookupKey.endsWith(SAFE_SUFFIX) && lookupKey.length() > SAFE_SUFFIX.length();
		String normalizedKey = safeVariant
			? lookupKey.substring(0, lookupKey.length() - SAFE_SUFFIX.length())
			: lookupKey;
		if (!normalizedKey.startsWith(LUNA_PREFIX)) {
			return null;
		}

		String key = normalizedKey.substring(LUNA_PREFIX.length());
		String value = resolvePaperLunaValue(key, snapshot);
		if (value == null) {
			return null;
		}

		return safeVariant ? escapePlaceholderPercents(value) : value;
	}

	private String resolvePaperLunaValue(String key, CurrentSnapshot snapshot) {
		String value = switch (key) {
			case "current_server" -> localServerName;
			case "status" -> server.isEnforceWhitelist() ? "MAINT" : "ONLINE";
			case "online" -> Integer.toString(server.getPlayerCount());
			case "max" -> Integer.toString(server.getMaxPlayers());
			case "tps" -> formatTps(snapshot.currentTps());
			case "player_ping" -> Integer.toString(snapshot.playerPingMillis());
			case "latency" -> "0";
			case "uptime" -> Formatters.compactDuration(Duration.ofMillis(snapshot.uptimeMillis()));
			case "uptime_long" -> Formatters.duration(Duration.ofMillis(snapshot.uptimeMillis()));
			case "uptime_ms" -> Long.toString(snapshot.uptimeMillis());
			case "system_cpu" -> formatPercent(snapshot.systemCpuPercent());
			case "process_cpu" -> formatPercent(snapshot.processCpuPercent());
			case "version" -> safe(server.getServerVersion());
			case "display" -> localServerName;
			case "host_name", "server_name" -> currentServerInfoName();
			case "color" -> DEFAULT_COLOR;
			case "whitelist" -> Boolean.toString(server.isEnforceWhitelist());
			case "total_entities" -> Integer.toString(snapshot.totalEntities());
			case "total_living_entities" -> Integer.toString(snapshot.totalLivingEntities());
			case "total_chunks" -> Integer.toString(snapshot.totalChunks());
			default -> null;
		};
		if (value != null) {
			return value;
		}

		value = resolveCurrentBar(key, "tps_bar", width -> buildBar(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "player_ping_bar", width -> buildBar(LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "latency_bar", width -> buildBar(LunaProgressBarPresets.latency("latency", 0D), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "system_cpu_bar", width -> buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "process_cpu_bar", width -> buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "ram_bar", width -> buildBar(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), width));
		if (value != null) {
			return value;
		}

		value = resolveCurrentBar(key, "tps_bar_only", width -> buildBarOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(key, "tps_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "player_ping_bar_only", width -> buildBarOnly(LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(key, "player_ping_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "latency_bar_only", width -> buildBarOnly(LunaProgressBarPresets.latency("latency", 0D), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(key, "latency_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.latency("latency", 0D)));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "system_cpu_bar_only", width -> buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(key, "system_cpu_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "process_cpu_bar_only", width -> buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = resolveExact(key, "process_cpu_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent())));
		if (value != null) {
			return value;
		}
		value = resolveCurrentBar(key, "ram_bar_only", width -> buildBarOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), width));
		if (value != null) {
			return value;
		}
		return resolveExact(key, "ram_bar_value_only", () -> buildValueOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes())));
	}

	private String resolveNativePlaceholder(ServerPlayer player, String rawIdentifier, String normalizedIdentifier, CurrentSnapshot snapshot) {
		if (normalizedIdentifier.startsWith(SERVER_TIME_PREFIX)) {
			return formatServerTime(rawIdentifier.substring(SERVER_TIME_PREFIX.length()));
		}

		return switch (normalizedIdentifier) {
			case SPARK_TICK_DURATION_10S -> formatDecimal(snapshot.currentTickDurationMillis());
			case "player_health" -> formatDecimal(Math.max(0D, player.getHealth()));
			case "player_health_rounded" -> Integer.toString(Math.max(0, Math.round(player.getHealth())));
			case "player_max_health" -> formatDecimal(Math.max(0D, player.getMaxHealth()));
			case "player_max_health_rounded" -> Integer.toString(Math.max(0, Math.round((float) player.getMaxHealth())));
			case "player_x" -> Integer.toString(player.getBlockX());
			case "player_y" -> Integer.toString(player.getBlockY());
			case "player_z" -> Integer.toString(player.getBlockZ());
			case "player_biome" -> currentBiomeName(player);
			case "server_name" -> currentServerInfoName();
			default -> null;
		};
	}

	private String formatServerTime(String pattern) {
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

	private String currentBiomeName(ServerPlayer player) {
		BlockPos blockPos = player.blockPosition();
		return player.serverLevel().getBiome(blockPos).unwrapKey()
			.map(key -> key.location().getPath())
			.orElse("unknown");
	}

	private String currentServerInfoName() {
		BackendMetadata metadata = currentBackendMetadataSupplier.get();
		if (metadata != null) {
			BackendMetadata sanitized = metadata.sanitize();
			if (sanitized.serverName() != null && !sanitized.serverName().isBlank()) {
				return sanitized.serverName();
			}
		}
		return localServerName;
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

	private String resolveCurrentBar(String key, String baseKey, java.util.function.IntFunction<String> renderer) {
		Integer width = parseCurrentWidth(key, baseKey);
		if (width == null) {
			return null;
		}
		return renderer.apply(width);
	}

	private String resolveExact(String key, String exactKey, java.util.function.Supplier<String> renderer) {
		if (!key.equals(exactKey)) {
			return null;
		}
		return renderer.get();
	}

	private Integer parseCurrentWidth(String key, String baseKey) {
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

	private void putCore(Map<String, String> values, String key, String value) {
		String normalized = safe(value);
		values.put(key, normalized);
		values.put("luna_" + key, normalized);
		values.put(key + "_safe", escapePlaceholderPercents(normalized));
		values.put("luna_" + key + "_safe", escapePlaceholderPercents(normalized));
	}

	private void putLunaAlias(Map<String, String> values, String key, String value) {
		String normalized = safe(value);
		values.put("luna_" + key, normalized);
		values.put("luna_" + key + "_safe", escapePlaceholderPercents(normalized));
	}

	private String buildBar(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).render();
	}

	private String buildBarOnly(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).renderBar();
	}

	private String buildValueOnly(LunaProgressBar bar) {
		return bar.renderValue();
	}

	private int clampWidth(int width) {
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

	private String formatPercent(double value) {
		return String.format(Locale.US, "%.1f%%", Math.max(0D, value));
	}

	private String formatTps(double value) {
		return String.format(Locale.US, "%.2f", Math.max(0D, value));
	}

	private String formatDecimal(double value) {
		String text = String.format(Locale.US, "%.2f", Math.max(0D, value));
		while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
			text = text.substring(0, text.length() - 1);
		}
		return text;
	}

	private String escapePlaceholderPercents(String value) {
		return safe(value).replace("%", "%%");
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private record CurrentSnapshot(
		double currentTps,
		double currentTickDurationMillis,
		int playerPingMillis,
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

	private record SharedSnapshot(
		double currentTps,
		double currentTickDurationMillis,
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

	@Override
	public void close() {
		closed = true;
		refreshExecutor.shutdownNow();
	}
}
