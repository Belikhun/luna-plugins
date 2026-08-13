package dev.belikhun.luna.legacy.permission;

import dev.belikhun.luna.legacy.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire shape of a resolved permission snapshot.
 *
 * Form encoding, not JSON, and that is a decision rather than an accident: this
 * module has no JSON parser and is not getting one for a single endpoint. The
 * heartbeat already speaks `application/x-www-form-urlencoded` in both directions, the
 * proxy already has an encoder for it, and reusing it means the permission mirror
 * carries no new dependency on either side.
 *
 * The field names below **are** the contract with
 * `VelocityPermissionHttpEndpoints#/permissions/resolve/{player}`. The two sides are
 * separate implementations in separate jars on separate Java versions, so a fixture
 * captured from the proxy is checked into `src/test` and asserted against - the same
 * arrangement that guards `HeartbeatFormCodec`.
 *
 * Counted collections rather than repeated keys: the form decoder collapses duplicate
 * keys (a `Map`), so `group=a&group=b` would silently keep one of them. Indexed
 * entries under a declared count is the idiom the heartbeat already uses for rows.
 */
public final class PermissionSnapshotCodec {
	/** Bumped when a field is added, removed or reinterpreted. */
	public static final int PROTOCOL_VERSION = 1;

	private PermissionSnapshotCodec() {
	}

	public static byte[] encode(PermissionSnapshot snapshot) {
		return HeartbeatFormCodec.encodeToString(fields(snapshot)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	public static Map<String, String> fields(PermissionSnapshot snapshot) {
		Map<String, String> out = new LinkedHashMap<String, String>();

		if (snapshot == null) {
			return out;
		}

		out.put("protocol", String.valueOf(PROTOCOL_VERSION));
		out.put("uuid", snapshot.uniqueId() == null ? "" : snapshot.uniqueId().toString());
		out.put("username", snapshot.username());
		out.put("primaryGroup", snapshot.primaryGroup());
		out.put("prefix", snapshot.prefix());
		out.put("suffix", snapshot.suffix());
		out.put("generatedAtEpochMillis", String.valueOf(snapshot.fetchedAtEpochMillis()));

		List<String> groups = snapshot.groups();
		out.put("groupCount", String.valueOf(groups.size()));

		for (int index = 0; index < groups.size(); index += 1) {
			out.put("group." + index, groups.get(index));
		}

		Map<String, Boolean> permissions = snapshot.permissions();
		out.put("permissionCount", String.valueOf(permissions.size()));

		int index = 0;

		for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
			out.put("perm." + index + ".key", entry.getKey());
			out.put("perm." + index + ".value", String.valueOf(entry.getValue().booleanValue()));
			index += 1;
		}

		return out;
	}

	/**
	 * Read a snapshot the proxy sent.
	 *
	 * @return the snapshot, or null when the body carries no usable uuid - which is
	 *         what an error page or a truncated response looks like from here, and is
	 *         reported as a miss rather than cached as an empty permission set
	 */
	public static PermissionSnapshot decode(byte[] body) {
		Map<String, String> fields = HeartbeatFormCodec.decode(body);
		UUID uniqueId = parseUuid(fields.get("uuid"));

		if (uniqueId == null) {
			return null;
		}

		List<String> groups = new ArrayList<String>();
		int groupCount = intValue(fields, "groupCount");

		for (int index = 0; index < groupCount; index += 1) {
			String group = string(fields, "group." + index);

			if (!Strings.isBlank(group)) {
				groups.add(group.trim());
			}
		}

		Map<String, Boolean> permissions = new LinkedHashMap<String, Boolean>();
		int permissionCount = intValue(fields, "permissionCount");

		for (int index = 0; index < permissionCount; index += 1) {
			String key = string(fields, "perm." + index + ".key");

			if (Strings.isBlank(key)) {
				continue;
			}

			permissions.put(key.trim(), Boolean.valueOf("true".equalsIgnoreCase(string(fields, "perm." + index + ".value"))));
		}

		// the proxy's clock stamps generatedAt, but the age this backend ages the entry
		// out on has to be measured against its own, or a clock skew of a minute either
		// expires every snapshot instantly or never expires one
		return new PermissionSnapshot(
			uniqueId,
			string(fields, "username"),
			string(fields, "primaryGroup"),
			string(fields, "prefix"),
			string(fields, "suffix"),
			groups,
			permissions,
			System.currentTimeMillis()
		);
	}

	private static UUID parseUuid(String raw) {
		if (Strings.isBlank(raw)) {
			return null;
		}

		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String string(Map<String, String> fields, String key) {
		String value = fields.get(key);

		return value == null ? "" : value;
	}

	private static int intValue(Map<String, String> fields, String key) {
		String value = fields.get(key);

		if (Strings.isBlank(value)) {
			return 0;
		}

		try {
			return Math.max(0, Integer.parseInt(value.trim()));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}
}
