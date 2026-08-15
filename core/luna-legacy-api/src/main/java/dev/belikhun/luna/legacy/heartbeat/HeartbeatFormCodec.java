package dev.belikhun.luna.legacy.heartbeat;

import dev.belikhun.luna.legacy.config.ConfigValues;
import dev.belikhun.luna.legacy.exception.LunaLegacyException;
import dev.belikhun.luna.legacy.string.Strings;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The form encoding a backend and the proxy exchange over the heartbeat.
 *
 * This is the file where the 1.12.2 port is most exposed. Everything else in this
 * module only has to *compile* at Java 8; this has to produce the same bytes as the
 * Java 21 build, because the proxy on the other end is that build. The field names,
 * their order, and `String.valueOf` on each numeric are all contract - see the
 * fixtures under `src/test`, which are captured from the modern encoder.
 *
 * Two Java 8 traps live here, and both are silent rather than obvious:
 *
 * - `URLEncoder.encode(String, Charset)` and its decode twin are **Java 10**. Java 8
 *   has only the `String` charset-name overloads, which declare a checked exception.
 *   Falling back to the deprecated single-argument form would encode using the
 *   platform default charset and corrupt every non-ASCII MOTD and display name.
 * - `String.valueOf(double)` is specified identically across versions, so `tps` and
 *   the cpu percentages travel the same. That is load-bearing and is why they are
 *   still formatted this way rather than through a formatter.
 */
public final class HeartbeatFormCodec {
	/**
	 * Wire protocol version. Bumped to 2 when field-level deltas were replaced by
	 * whole rows plus a registry epoch - the two sides are deployed together, so a
	 * mismatch is a deployment error and is reported as one instead of being papered
	 * over.
	 */
	public static final int PROTOCOL_VERSION = 2;

	private static final String UTF_8 = StandardCharsets.UTF_8.name();

	private HeartbeatFormCodec() {
	}

	public static Map<String, String> decode(byte[] body) {
		return decode(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
	}

	public static Map<String, String> decode(String raw) {
		Map<String, String> out = new LinkedHashMap<String, String>();

		if (Strings.isBlank(raw)) {
			return out;
		}

		for (String pair : raw.split("&")) {
			if (Strings.isBlank(pair)) {
				continue;
			}

			String[] entry = pair.split("=", 2);
			String key = decodePart(entry[0]);

			if (Strings.isBlank(key)) {
				continue;
			}

			out.put(key, entry.length > 1 ? decodePart(entry[1]) : "");
		}

		return out;
	}

	public static byte[] encode(Map<String, String> values) {
		return encodeToString(values).getBytes(StandardCharsets.UTF_8);
	}

	public static String encodeToString(Map<String, String> values) {
		if (values == null || values.isEmpty()) {
			return "";
		}

		StringBuilder out = new StringBuilder();

		for (Map.Entry<String, String> entry : values.entrySet()) {
			if (Strings.isBlank(entry.getKey())) {
				continue;
			}

			if (out.length() > 0) {
				out.append('&');
			}

			out.append(encodePart(entry.getKey()));
			out.append('=');
			out.append(encodePart(entry.getValue() == null ? "" : entry.getValue()));
		}

		return out.toString();
	}

	public static BackendHeartbeatStats decodeStats(Map<String, String> fields) {
		return new BackendHeartbeatStats(
			string(fields, "software", "unknown"),
			string(fields, "version", "unknown"),
			intValue(fields, "serverPort", 0),
			longValue(fields, "uptimeMillis", 0L),
			doubleValue(fields, "tps", 0D),
			intValue(fields, "onlinePlayers", 0),
			intValue(fields, "maxPlayers", 0),
			string(fields, "motd", ""),
			boolValue(fields, "whitelistEnabled", false),
			doubleValue(fields, "systemCpuUsagePercent", doubleValue(fields, "cpuUsagePercent", 0D)),
			doubleValue(fields, "processCpuUsagePercent", 0D),
			longValue(fields, "ramUsedBytes", 0L),
			longValue(fields, "ramFreeBytes", 0L),
			longValue(fields, "ramMaxBytes", 0L),
			longValue(fields, "heartbeatLatencyMillis", 0L),
			decodeWorlds(fields),
			decodeTicks(fields)
		);
	}

	private static List<ServerWorldStats> decodeWorlds(Map<String, String> fields) {
		int count = intValue(fields, "worldCount", 0);
		if (count <= 0) {
			return java.util.Collections.<ServerWorldStats>emptyList();
		}

		List<ServerWorldStats> worlds = new ArrayList<ServerWorldStats>(count);
		for (int index = 0; index < count; index++) {
			String prefix = "world." + index + ".";
			String name = string(fields, prefix + "name", "").trim();
			if (name.isEmpty()) {
				continue;
			}

			worlds.add(new ServerWorldStats(
				name,
				intValue(fields, prefix + "chunks", 0),
				intValue(fields, prefix + "ticking", 0),
				intValue(fields, prefix + "nonTicking", 0)
			));
		}

		return java.util.Collections.unmodifiableList(worlds);
	}

	private static ServerTickStats decodeTicks(Map<String, String> fields) {
		long samples = longValue(fields, "tickSamples", 0L);
		if (samples <= 0L) {
			return ServerTickStats.UNKNOWN;
		}

		return new ServerTickStats(
			samples,
			doubleValue(fields, "tickMeanMillis", -1D),
			doubleValue(fields, "tickMaxMillis", -1D),
			doubleValue(fields, "tickApdex", -1D),
			doubleValue(fields, "tickMisery", -1D)
		);
	}

	/**
	 * Per-world counters, as indexed keys inside the row.
	 *
	 * The count is written after the loop rather than before it, for the same
	 * reason the row loop does it: a world dropped for having no name would leave
	 * the receiver reading past the end of what was actually encoded.
	 */
	private static void encodeWorlds(Map<String, String> out, List<ServerWorldStats> worlds) {
		if (worlds == null || worlds.isEmpty()) {
			return;
		}

		int index = 0;
		for (ServerWorldStats world : worlds) {
			if (world == null || world.name().trim().isEmpty()) {
				continue;
			}

			String prefix = "world." + index + ".";
			out.put(prefix + "name", world.name());
			out.put(prefix + "chunks", String.valueOf(world.loadedChunks()));
			out.put(prefix + "ticking", String.valueOf(world.tickingEntities()));
			out.put(prefix + "nonTicking", String.valueOf(world.nonTickingEntities()));
			index++;
		}

		out.put("worldCount", String.valueOf(index));
	}

	/** Tick indices, written only when something was actually measured. */
	private static void encodeTicks(Map<String, String> out, ServerTickStats ticks) {
		if (ticks == null || !ticks.known()) {
			return;
		}

		out.put("tickSamples", String.valueOf(ticks.samples()));
		out.put("tickMeanMillis", String.valueOf(ticks.meanMillis()));
		out.put("tickMaxMillis", String.valueOf(ticks.maxMillis()));
		out.put("tickApdex", String.valueOf(ticks.apdex()));
		out.put("tickMisery", String.valueOf(ticks.misery()));
	}

	public static Map<String, String> encodeStats(BackendHeartbeatStats stats) {
		Map<String, String> out = new LinkedHashMap<String, String>();

		if (stats == null) {
			return out;
		}

		out.put("software", emptySafe(stats.software()));
		out.put("version", emptySafe(stats.version()));
		out.put("serverPort", String.valueOf(stats.serverPort()));
		out.put("uptimeMillis", String.valueOf(stats.uptimeMillis()));
		out.put("tps", String.valueOf(stats.tps()));
		out.put("onlinePlayers", String.valueOf(stats.onlinePlayers()));
		out.put("maxPlayers", String.valueOf(stats.maxPlayers()));
		out.put("motd", emptySafe(stats.motd()));
		out.put("whitelistEnabled", String.valueOf(stats.whitelistEnabled()));
		out.put("systemCpuUsagePercent", String.valueOf(stats.systemCpuUsagePercent()));
		out.put("processCpuUsagePercent", String.valueOf(stats.processCpuUsagePercent()));
		out.put("cpuUsagePercent", String.valueOf(stats.systemCpuUsagePercent()));
		out.put("ramUsedBytes", String.valueOf(stats.ramUsedBytes()));
		out.put("ramFreeBytes", String.valueOf(stats.ramFreeBytes()));
		out.put("ramMaxBytes", String.valueOf(stats.ramMaxBytes()));
		out.put("heartbeatLatencyMillis", String.valueOf(stats.heartbeatLatencyMillis()));
		encodeWorlds(out, stats.worlds());
		encodeTicks(out, stats.ticks());

		return out;
	}

	/**
	 * Encode a set of whole registry rows.
	 *
	 * The same encoding serves the heartbeat response and every event on the registry
	 * stream: a stream event is simply a payload carrying one row, which lets the
	 * client decode both with {@link #decodeSnapshotPayload(byte[])}.
	 *
	 * @param fullSync tells the receiver to replace its mirror rather than merge
	 * @param epoch    identifies the registry generation the revisions belong to
	 */
	public static byte[] encodeRows(
		Collection<BackendStatusRow> rows,
		long revision,
		String epoch,
		boolean fullSync,
		String selfServerName,
		BackendMetadata currentBackendMetadata
	) {
		Map<String, String> out = new LinkedHashMap<String, String>();

		out.put("protocol", String.valueOf(PROTOCOL_VERSION));
		out.put("epoch", emptySafe(epoch));
		out.put("revision", String.valueOf(Math.max(0L, revision)));
		out.put("fullSync", String.valueOf(fullSync));
		out.put("serverCount", String.valueOf(rows == null ? 0 : rows.size()));
		encodeCurrentBackendMetadata(out, currentBackendMetadata);

		if (rows == null || rows.isEmpty()) {
			return encode(out);
		}

		String normalizedSelf = normalize(selfServerName);
		int index = 0;

		for (BackendStatusRow row : rows) {
			if (row == null || row.status() == null) {
				continue;
			}

			BackendServerStatus status = row.status();
			String prefix = "server." + index + ".";

			out.put(prefix + "server_name", emptySafe(status.serverName()));
			out.put(prefix + "server_display", emptySafe(status.serverDisplay()));
			out.put(prefix + "server_accent_color", emptySafe(status.serverAccentColor()));
			out.put(prefix + "name", emptySafe(status.serverName()));
			out.put(prefix + "online", String.valueOf(status.online()));
			out.put(prefix + "lastHeartbeatEpochMillis", String.valueOf(status.lastHeartbeatEpochMillis()));
			out.put(prefix + "revision", String.valueOf(Math.max(0L, row.revision())));
			out.put(prefix + "self", String.valueOf(row.self() || normalize(status.serverName()).equals(normalizedSelf)));

			for (Map.Entry<String, String> entry : encodeStats(status.stats()).entrySet()) {
				out.put(prefix + entry.getKey(), entry.getValue());
			}

			index += 1;
		}

		// serverCount is written before the loop, so rows dropped for being blank
		// would leave the receiver reading past the end
		out.put("serverCount", String.valueOf(index));

		return encode(out);
	}

	public static HeartbeatSnapshotPayload decodeSnapshotPayload(byte[] body) {
		Map<String, String> fields = decode(body);
		List<BackendStatusRow> rows = new ArrayList<BackendStatusRow>();

		int protocol = intValue(fields, "protocol", 0);
		String epoch = string(fields, "epoch", "");
		long revision = longValue(fields, "revision", 0L);
		boolean fullSync = boolValue(fields, "fullSync", true);
		int count = intValue(fields, "serverCount", 0);
		BackendMetadata currentBackendMetadata = decodeCurrentBackendMetadata(fields);

		for (int index = 0; index < count; index += 1) {
			String prefix = "server." + index + ".";
			String name = string(fields, prefix + "server_name", string(fields, prefix + "name", "")).trim();

			if (Strings.isBlank(name)) {
				continue;
			}

			boolean self = boolValue(fields, prefix + "self", false);
			BackendServerStatus status = new BackendServerStatus(
				name,
				string(fields, prefix + "server_display", name),
				string(fields, prefix + "server_accent_color", ""),
				boolValue(fields, prefix + "online", false),
				longValue(fields, prefix + "lastHeartbeatEpochMillis", 0L),
				decodeStats(withPrefix(fields, prefix))
			);

			if (self && currentBackendMetadata == null) {
				currentBackendMetadata = status.metadata();
			}

			rows.add(new BackendStatusRow(status, longValue(fields, prefix + "revision", revision), self));
		}

		return new HeartbeatSnapshotPayload(
			protocol,
			epoch,
			Math.max(0L, revision),
			fullSync,
			rows,
			currentBackendMetadata
		);
	}

	private static void encodeCurrentBackendMetadata(Map<String, String> out, BackendMetadata currentBackendMetadata) {
		BackendMetadata sanitized = currentBackendMetadata == null ? null : currentBackendMetadata.sanitize();

		if (sanitized == null || sanitized.isBlank()) {
			return;
		}

		out.put("currentBackendName", emptySafe(sanitized.name()));
		out.put("currentBackendDisplay", emptySafe(sanitized.displayName()));
		out.put("currentBackendAccentColor", emptySafe(sanitized.accentColor()));
		out.put("currentBackendServerName", emptySafe(sanitized.serverName()));
	}

	private static BackendMetadata decodeCurrentBackendMetadata(Map<String, String> fields) {
		String currentName = string(fields, "currentBackendName", "").trim();

		if (Strings.isBlank(currentName)) {
			return null;
		}

		return new BackendMetadata(
			currentName,
			string(fields, "currentBackendDisplay", currentName),
			string(fields, "currentBackendAccentColor", ""),
			string(fields, "currentBackendServerName", currentName)
		).sanitize();
	}

	private static Map<String, String> withPrefix(Map<String, String> fields, String prefix) {
		Map<String, String> out = new LinkedHashMap<String, String>();

		for (Map.Entry<String, String> entry : fields.entrySet()) {
			if (!entry.getKey().startsWith(prefix)) {
				continue;
			}

			out.put(entry.getKey().substring(prefix.length()), entry.getValue());
		}

		return out;
	}

	private static String string(Map<String, String> fields, String key, String fallback) {
		String value = fields.get(key);

		return value == null ? fallback : value;
	}

	private static int intValue(Map<String, String> fields, String key, int fallback) {
		String value = fields.get(key);

		if (Strings.isBlank(value)) {
			return fallback;
		}

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static long longValue(Map<String, String> fields, String key, long fallback) {
		String value = fields.get(key);

		if (Strings.isBlank(value)) {
			return fallback;
		}

		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double doubleValue(Map<String, String> fields, String key, double fallback) {
		String value = fields.get(key);

		if (Strings.isBlank(value)) {
			return fallback;
		}

		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static boolean boolValue(Map<String, String> fields, String key, boolean fallback) {
		return ConfigValues.booleanValue(fields, key, fallback);
	}

	// URLEncoder/URLDecoder only take a Charset from Java 10. The name overloads
	// declare UnsupportedEncodingException, which cannot happen for a charset the
	// JVM is required to support - so it is wrapped rather than propagated, and
	// every caller stays as readable as the modern one.
	private static String encodePart(String value) {
		try {
			return URLEncoder.encode(value == null ? "" : value, UTF_8);
		} catch (UnsupportedEncodingException impossible) {
			throw new LunaLegacyException("UTF-8 không khả dụng khi mã hoá heartbeat.", impossible);
		}
	}

	private static String decodePart(String value) {
		try {
			return URLDecoder.decode(value == null ? "" : value, UTF_8);
		} catch (UnsupportedEncodingException impossible) {
			throw new LunaLegacyException("UTF-8 không khả dụng khi giải mã heartbeat.", impossible);
		}
	}

	private static String emptySafe(String value) {
		return value == null ? "" : value;
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}

		return value.trim().toLowerCase(Locale.ROOT);
	}
}
