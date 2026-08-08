package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigValues;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HeartbeatFormCodec {
	/**
	 * Wire protocol version. Bumped to 2 when field-level deltas were replaced by
	 * whole rows plus a registry epoch — the two sides are deployed together, so a
	 * mismatch is a deployment error and is reported as one instead of being
	 * papered over.
	 */
	public static final int PROTOCOL_VERSION = 2;

	private HeartbeatFormCodec() {
	}

	public static Map<String, String> decode(byte[] body) {
		String raw = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
		return decode(raw);
	}

	public static Map<String, String> decode(String raw) {
		Map<String, String> out = new LinkedHashMap<>();
		if (raw == null || raw.isBlank()) {
			return out;
		}

		String[] pairs = raw.split("&");
		for (String pair : pairs) {
			if (pair == null || pair.isBlank()) {
				continue;
			}

			String[] entry = pair.split("=", 2);
			String key = decodePart(entry[0]);
			if (key.isBlank()) {
				continue;
			}

			String value = entry.length > 1 ? decodePart(entry[1]) : "";
			out.put(key, value);
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
			if (entry.getKey() == null || entry.getKey().isBlank()) {
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
			longValue(fields, "heartbeatLatencyMillis", 0L)
		);
	}

	public static Map<String, String> encodeStats(BackendHeartbeatStats stats) {
		Map<String, String> out = new LinkedHashMap<>();
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
		return out;
	}

	/**
	 * Encode a set of whole registry rows.
	 *
	 * The same encoding serves the heartbeat response and every event on the
	 * registry stream: a stream event is simply a payload carrying one row, which
	 * lets the client decode both with {@link #decodeSnapshotPayload(byte[])}.
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
		Map<String, String> out = new LinkedHashMap<>();
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
			Map<String, String> stats = encodeStats(status.stats());
			for (Map.Entry<String, String> entry : stats.entrySet()) {
				out.put(prefix + entry.getKey(), entry.getValue());
			}
			index++;
		}

		// serverCount is written before the loop, so rows dropped for being blank
		// would leave the receiver reading past the end
		out.put("serverCount", String.valueOf(index));
		return encode(out);
	}

	public static HeartbeatSnapshotPayload decodeSnapshotPayload(byte[] body) {
		Map<String, String> fields = decode(body);
		List<BackendStatusRow> rows = new ArrayList<>();
		int protocol = intValue(fields, "protocol", 0);
		String epoch = string(fields, "epoch", "");
		long revision = longValue(fields, "revision", 0L);
		boolean fullSync = boolValue(fields, "fullSync", true);
		int count = intValue(fields, "serverCount", 0);
		BackendMetadata currentBackendMetadata = decodeCurrentBackendMetadata(fields);
		for (int index = 0; index < count; index++) {
			String prefix = "server." + index + ".";
			String name = string(fields, prefix + "server_name", string(fields, prefix + "name", "")).trim();
			if (name.isBlank()) {
				continue;
			}

			boolean self = boolValue(fields, prefix + "self", false);
			BackendHeartbeatStats stats = decodeStats(withPrefix(fields, prefix));
			BackendServerStatus status = new BackendServerStatus(
				name,
				string(fields, prefix + "server_display", name),
				string(fields, prefix + "server_accent_color", ""),
				boolValue(fields, prefix + "online", false),
				longValue(fields, prefix + "lastHeartbeatEpochMillis", 0L),
				stats
			);
			if (self && currentBackendMetadata == null) {
				currentBackendMetadata = status.metadata();
			}
			rows.add(new BackendStatusRow(status, longValue(fields, prefix + "revision", revision), self));
		}

		return new HeartbeatSnapshotPayload(protocol, epoch, Math.max(0L, revision), fullSync, rows, currentBackendMetadata);
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
		if (currentName.isBlank()) {
			return null;
		}

		return new BackendMetadata(
			currentName,
			string(fields, "currentBackendDisplay", currentName),
			string(fields, "currentBackendAccentColor", ""),
			string(fields, "currentBackendServerName", currentName)
		).sanitize();
	}

	/**
	 * A decoded registry payload: either a full mirror or the rows that changed
	 * since the caller's cursor.
	 *
	 * @param protocol the sender's {@link #PROTOCOL_VERSION}, 0 when it predates the field
	 * @param epoch    the registry generation; a change means the cursor is meaningless
	 */
	public record HeartbeatSnapshotPayload(
		int protocol,
		String epoch,
		long revision,
		boolean fullSync,
		List<BackendStatusRow> rows,
		BackendMetadata currentBackendMetadata
	) {
	}

	private static Map<String, String> withPrefix(Map<String, String> fields, String prefix) {
		Map<String, String> out = new LinkedHashMap<>();
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
		if (value == null) {
			return fallback;
		}
		return value;
	}

	private static int intValue(Map<String, String> fields, String key, int fallback) {
		String value = fields.get(key);
		if (value == null || value.isBlank()) {
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
		if (value == null || value.isBlank()) {
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
		if (value == null || value.isBlank()) {
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

	private static String encodePart(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static String decodePart(String value) {
		return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
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
