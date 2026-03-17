package dev.belikhun.luna.core.api.heartbeat;

import dev.belikhun.luna.core.api.config.ConfigValues;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HeartbeatFormCodec {
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

	public static byte[] encodeSnapshot(Map<String, BackendServerStatus> snapshot) {
		return encodeSnapshot(snapshot, 0L, null, true);
	}

	public static byte[] encodeSnapshot(Map<String, BackendServerStatus> snapshot, long revision, String selfServerName, boolean fullSync) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("revision", String.valueOf(Math.max(0L, revision)));
		out.put("fullSync", String.valueOf(fullSync));
		out.put("serverCount", String.valueOf(snapshot == null ? 0 : snapshot.size()));
		if (snapshot == null || snapshot.isEmpty()) {
			return encode(out);
		}

		String normalizedSelf = normalize(selfServerName);

		int index = 0;
		for (BackendServerStatus status : snapshot.values()) {
			String prefix = "server." + index + ".";
			out.put(prefix + "server_name", emptySafe(status.serverName()));
			out.put(prefix + "server_display", emptySafe(status.serverDisplay()));
			out.put(prefix + "server_accent_color", emptySafe(status.serverAccentColor()));
			out.put(prefix + "name", emptySafe(status.serverName()));
			out.put(prefix + "online", String.valueOf(status.online()));
			out.put(prefix + "lastHeartbeatEpochMillis", String.valueOf(status.lastHeartbeatEpochMillis()));
			out.put(prefix + "self", String.valueOf(normalize(status.serverName()).equals(normalizedSelf)));
			Map<String, String> stats = encodeStats(status.stats());
			for (Map.Entry<String, String> entry : stats.entrySet()) {
				out.put(prefix + entry.getKey(), entry.getValue());
			}
			index++;
		}

		return encode(out);
	}

	public static byte[] encodeDelta(Map<String, BackendServerStatusDelta> delta, long revision, String selfServerName) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("revision", String.valueOf(Math.max(0L, revision)));
		out.put("fullSync", String.valueOf(false));
		out.put("serverCount", String.valueOf(delta == null ? 0 : delta.size()));
		if (delta == null || delta.isEmpty()) {
			return encode(out);
		}

		String normalizedSelf = normalize(selfServerName);
		int index = 0;
		for (BackendServerStatusDelta serverDelta : delta.values()) {
			String prefix = "server." + index + ".";
			String serverName = emptySafe(serverDelta.serverName());
			out.put(prefix + "server_name", serverName);
			out.put(prefix + "name", serverName);
			out.put(prefix + "self", String.valueOf(serverDelta.self() || normalize(serverDelta.serverName()).equals(normalizedSelf)));
			if (serverDelta.serverDisplay() != null) {
				out.put(prefix + "server_display", serverDelta.serverDisplay());
			}
			if (serverDelta.serverAccentColor() != null) {
				out.put(prefix + "server_accent_color", serverDelta.serverAccentColor());
			}
			if (serverDelta.online() != null) {
				out.put(prefix + "online", String.valueOf(serverDelta.online()));
			}
			if (serverDelta.lastHeartbeatEpochMillis() != null) {
				out.put(prefix + "lastHeartbeatEpochMillis", String.valueOf(serverDelta.lastHeartbeatEpochMillis()));
			}
			encodeStatsDelta(prefix, out, serverDelta.stats());
			index++;
		}

		return encode(out);
	}

	public static Map<String, BackendServerStatus> decodeSnapshot(byte[] body) {
		return decodeSnapshotPayload(body).statuses();
	}

	public static HeartbeatSnapshotPayload decodeSnapshotPayload(byte[] body) {
		Map<String, String> fields = decode(body);
		Map<String, BackendServerStatus> out = new LinkedHashMap<>();
		Map<String, BackendServerStatusDelta> deltas = new LinkedHashMap<>();
		long revision = longValue(fields, "revision", 0L);
		boolean fullSync = boolValue(fields, "fullSync", true);
		int count = intValue(fields, "serverCount", 0);
		BackendMetadata currentBackendMetadata = null;
		for (int index = 0; index < count; index++) {
			String prefix = "server." + index + ".";
			String name = string(fields, prefix + "server_name", string(fields, prefix + "name", "")).trim();
			if (name.isBlank()) {
				continue;
			}
			boolean self = boolValue(fields, prefix + "self", false);

			if (!fullSync) {
				Map<String, String> prefixed = withPrefix(fields, prefix);
				BackendServerStatusDelta delta = new BackendServerStatusDelta(
					name,
					self,
					prefixed.containsKey("server_display") ? prefixed.get("server_display") : null,
					prefixed.containsKey("server_accent_color") ? prefixed.get("server_accent_color") : null,
					prefixed.containsKey("online") ? boolValue(prefixed, "online", false) : null,
					prefixed.containsKey("lastHeartbeatEpochMillis") ? longValue(prefixed, "lastHeartbeatEpochMillis", 0L) : null,
					decodeStatsDelta(prefixed)
				);
				deltas.put(name.toLowerCase(), delta);
				continue;
			}

			BackendHeartbeatStats stats = decodeStats(withPrefix(fields, prefix));
			BackendServerStatus status = new BackendServerStatus(
				name,
				string(fields, prefix + "server_display", name),
				string(fields, prefix + "server_accent_color", ""),
				boolValue(fields, prefix + "online", false),
				longValue(fields, prefix + "lastHeartbeatEpochMillis", 0L),
				stats
			);
			if (self) {
				currentBackendMetadata = status.metadata();
			}
			out.put(name.toLowerCase(), status);
		}

		return new HeartbeatSnapshotPayload(Math.max(0L, revision), fullSync, out, deltas, currentBackendMetadata);
	}

	public record HeartbeatSnapshotPayload(
		long revision,
		boolean fullSync,
		Map<String, BackendServerStatus> statuses,
		Map<String, BackendServerStatusDelta> deltas,
		BackendMetadata currentBackendMetadata
	) {
	}

	private static void encodeStatsDelta(String prefix, Map<String, String> out, BackendHeartbeatStatsDelta delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		putIfPresent(out, prefix + "software", delta.software());
		putIfPresent(out, prefix + "version", delta.version());
		putIfPresent(out, prefix + "serverPort", delta.serverPort());
		putIfPresent(out, prefix + "uptimeMillis", delta.uptimeMillis());
		putIfPresent(out, prefix + "tps", delta.tps());
		putIfPresent(out, prefix + "onlinePlayers", delta.onlinePlayers());
		putIfPresent(out, prefix + "maxPlayers", delta.maxPlayers());
		putIfPresent(out, prefix + "motd", delta.motd());
		putIfPresent(out, prefix + "whitelistEnabled", delta.whitelistEnabled());
		putIfPresent(out, prefix + "systemCpuUsagePercent", delta.systemCpuUsagePercent());
		putIfPresent(out, prefix + "processCpuUsagePercent", delta.processCpuUsagePercent());
		putIfPresent(out, prefix + "cpuUsagePercent", delta.systemCpuUsagePercent());
		putIfPresent(out, prefix + "ramUsedBytes", delta.ramUsedBytes());
		putIfPresent(out, prefix + "ramFreeBytes", delta.ramFreeBytes());
		putIfPresent(out, prefix + "ramMaxBytes", delta.ramMaxBytes());
		putIfPresent(out, prefix + "heartbeatLatencyMillis", delta.heartbeatLatencyMillis());
	}

	private static BackendHeartbeatStatsDelta decodeStatsDelta(Map<String, String> fields) {
		return new BackendHeartbeatStatsDelta(
			fields.containsKey("software") ? fields.get("software") : null,
			fields.containsKey("version") ? fields.get("version") : null,
			fields.containsKey("serverPort") ? intValue(fields, "serverPort", 0) : null,
			fields.containsKey("uptimeMillis") ? longValue(fields, "uptimeMillis", 0L) : null,
			fields.containsKey("tps") ? doubleValue(fields, "tps", 0D) : null,
			fields.containsKey("onlinePlayers") ? intValue(fields, "onlinePlayers", 0) : null,
			fields.containsKey("maxPlayers") ? intValue(fields, "maxPlayers", 0) : null,
			fields.containsKey("motd") ? fields.get("motd") : null,
			fields.containsKey("whitelistEnabled") ? boolValue(fields, "whitelistEnabled", false) : null,
			fields.containsKey("systemCpuUsagePercent") ? doubleValue(fields, "systemCpuUsagePercent", doubleValue(fields, "cpuUsagePercent", 0D)) : null,
			fields.containsKey("processCpuUsagePercent") ? doubleValue(fields, "processCpuUsagePercent", 0D) : null,
			fields.containsKey("ramUsedBytes") ? longValue(fields, "ramUsedBytes", 0L) : null,
			fields.containsKey("ramFreeBytes") ? longValue(fields, "ramFreeBytes", 0L) : null,
			fields.containsKey("ramMaxBytes") ? longValue(fields, "ramMaxBytes", 0L) : null,
			fields.containsKey("heartbeatLatencyMillis") ? longValue(fields, "heartbeatLatencyMillis", 0L) : null
		);
	}

	private static void putIfPresent(Map<String, String> out, String key, Object value) {
		if (value != null) {
			out.put(key, String.valueOf(value));
		}
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
