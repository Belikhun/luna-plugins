package dev.belikhun.luna.legacy.tabbridge;

import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The relational values luna publishes when nothing else does.
 *
 * Every online player is a target, so this is O(online) per viewer and O(online²)
 * per refresh. That is affordable because the values are pure lookups against a
 * permission snapshot already in memory, and because the runtime only sends the
 * ones that changed.
 */
public final class BuiltInTabBridgeRelationalPlaceholderSource<P> implements TabBridgeRelationalPlaceholderSource<P> {
	private static final String REL_PLAYER_NAME = "%rel_luna_player_name%";
	private static final String REL_PLAYER_GROUP_NAME = "%rel_luna_player_group_name%";
	private static final String REL_PLAYER_GROUP_DISPLAY = "%rel_luna_player_group_display%";
	private static final String REL_PLAYER_PREFIX = "%rel_luna_player_prefix%";
	private static final String REL_PLAYER_SUFFIX = "%rel_luna_player_suffix%";
	private static final String REL_PLAYER_DISPLAY = "%rel_luna_player_display%";
	private static final String DEFAULT_DISPLAY_TEMPLATE = "%player_prefix% %displayname%";

	private final TabPlayerBridge<P> players;
	private final PermissionService permissions;

	public BuiltInTabBridgeRelationalPlaceholderSource(TabPlayerBridge<P> players, PermissionService permissions) {
		this.players = players;
		this.permissions = permissions;
	}

	@Override
	public Map<String, Map<String, String>> resolve(P viewer) {
		if (viewer == null || players == null) {
			return Collections.emptyMap();
		}

		Map<String, String> names = new LinkedHashMap<String, String>();
		Map<String, String> groupNames = new LinkedHashMap<String, String>();
		Map<String, String> groupDisplays = new LinkedHashMap<String, String>();
		Map<String, String> prefixes = new LinkedHashMap<String, String>();
		Map<String, String> suffixes = new LinkedHashMap<String, String>();
		Map<String, String> displays = new LinkedHashMap<String, String>();

		for (P target : players.online()) {
			if (target == null) {
				continue;
			}

			String targetName = safe(players.nameOf(target));

			if (Strings.isBlank(targetName)) {
				continue;
			}

			UUID targetId = players.idOf(target);
			String prefix = resolvePrefix(targetId);
			String suffix = resolveSuffix(targetId);
			String groupName = resolveGroupName(targetId);

			names.put(targetName, targetName);
			groupNames.put(targetName, groupName);

			// LuckPerms falls back to the group's own name when no `displayname` meta
			// is set, and the mirror carries no meta at all, so this line is that
			// fallback taken always rather than a value quietly going missing
			groupDisplays.put(targetName, groupName);

			prefixes.put(targetName, prefix);
			suffixes.put(targetName, suffix);
			displays.put(targetName, formatDisplay(targetName, prefix, suffix));
		}

		Map<String, Map<String, String>> values = new LinkedHashMap<String, Map<String, String>>();

		put(values, REL_PLAYER_NAME, names);
		put(values, REL_PLAYER_GROUP_NAME, groupNames);
		put(values, REL_PLAYER_GROUP_DISPLAY, groupDisplays);
		put(values, REL_PLAYER_PREFIX, prefixes);
		put(values, REL_PLAYER_SUFFIX, suffixes);
		put(values, REL_PLAYER_DISPLAY, displays);

		return values;
	}

	private void put(Map<String, Map<String, String>> output, String identifier, Map<String, String> values) {
		if (values.isEmpty()) {
			return;
		}

		output.put(identifier, Collections.unmodifiableMap(values));
	}

	private String resolveGroupName(UUID playerId) {
		if (playerId == null || permissions == null) {
			return "";
		}

		return safe(permissions.groupName(playerId));
	}

	private String resolvePrefix(UUID playerId) {
		if (playerId == null || permissions == null) {
			return "";
		}

		return safe(permissions.prefix(playerId));
	}

	private String resolveSuffix(UUID playerId) {
		if (playerId == null || permissions == null) {
			return "";
		}

		return safe(permissions.suffix(playerId));
	}

	private String formatDisplay(String playerName, String prefix, String suffix) {
		String rendered = DEFAULT_DISPLAY_TEMPLATE
			.replace("%player_prefix%", safe(prefix))
			.replace("%player_suffix%", safe(suffix))
			.replace("%displayname%", safe(playerName));

		String normalized = rendered.trim();

		return normalized.isEmpty() ? safe(playerName) : normalized;
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
