package dev.belikhun.luna.core.api.placeholder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The server facts one resolution round reads, taken on the server thread and
 * then immutable.
 *
 * Resolvers never touch the server; they read this. A platform builds one on a
 * tick cadence through its {@link PlaceholderHost} and hands it to the engine.
 *
 * @param backendName           luna's name for this backend, {@code %luna_current_server%}
 * @param hostName              the name the proxy routes to, {@code %luna_host_name%}
 * @param displayName           the display name from the proxy's metadata, or the backend name
 * @param accentColor           the accent colour from the proxy's metadata, or the default
 * @param version               the server software version string
 * @param onlinePlayers         players online on this backend
 * @param maxPlayers            the slot count
 * @param whitelistEnabled      whether the whitelist is on, which luna reads as maintenance
 * @param tps                   ticks per second over the recent window
 * @param tickDurationMillis    mean tick duration over the recent window
 * @param uptimeMillis          how long the process has run
 * @param systemCpuPercent      system CPU load, 0..100
 * @param processCpuPercent     this process's CPU load, 0..100
 * @param ramUsedBytes          heap in use
 * @param ramMaxBytes           heap ceiling
 * @param totalEntities         entities across every world
 * @param totalLivingEntities   living entities across every world
 * @param totalChunks           loaded chunks across every world
 * @param heartbeatLatencyMillis the proxy round trip the heartbeat measured, 0 when unknown
 * @param worlds                weather per world, keyed by lowercase world name
 * @param sampledAtEpochMillis  when this sample was taken
 */
public record ServerSample(
	String backendName,
	String hostName,
	String displayName,
	String accentColor,
	String version,
	int onlinePlayers,
	int maxPlayers,
	boolean whitelistEnabled,
	double tps,
	double tickDurationMillis,
	long uptimeMillis,
	double systemCpuPercent,
	double processCpuPercent,
	long ramUsedBytes,
	long ramMaxBytes,
	int totalEntities,
	int totalLivingEntities,
	int totalChunks,
	long heartbeatLatencyMillis,
	Map<String, WorldWeather> worlds,
	long sampledAtEpochMillis
) {
	/** The accent colour used when the proxy has not said otherwise. */
	public static final String DEFAULT_ACCENT_COLOR = "#F1FF68";

	public ServerSample {
		backendName = blankToDefault(backendName, "unknown");
		hostName = blankToDefault(hostName, backendName);
		displayName = blankToDefault(displayName, backendName);
		accentColor = blankToDefault(accentColor, DEFAULT_ACCENT_COLOR);
		version = blankToDefault(version, "unknown");
		worlds = worlds == null ? Map.of() : Map.copyOf(worlds);
	}

	/** A sample with nothing in it, for before the first tick. */
	public static ServerSample empty() {
		return new ServerSample("unknown", null, null, null, null, 0, 0, false, 0D, 0D, 0L, 0D, 0D, 0L, 0L, 0, 0, 0, 0L, Map.of(), 0L);
	}

	/** Weather for a world by name, case-insensitive; null when unknown. */
	public WorldWeather world(String name) {
		if (name == null) {
			return null;
		}

		return worlds.get(name.toLowerCase(Locale.ROOT));
	}

	/** Builds the worlds map from a list, keyed the way {@link #world} looks up. */
	public static Map<String, WorldWeather> indexWorlds(Iterable<WorldWeather> weather) {
		Map<String, WorldWeather> indexed = new LinkedHashMap<>();

		if (weather == null) {
			return indexed;
		}

		for (WorldWeather world : weather) {
			if (world != null && world.name() != null) {
				indexed.put(world.name().toLowerCase(Locale.ROOT), world);
			}
		}

		return indexed;
	}

	/** Age of this sample in milliseconds. */
	public long ageMillis() {
		return sampledAtEpochMillis <= 0L ? -1L : Math.max(0L, System.currentTimeMillis() - sampledAtEpochMillis);
	}

	private static String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
