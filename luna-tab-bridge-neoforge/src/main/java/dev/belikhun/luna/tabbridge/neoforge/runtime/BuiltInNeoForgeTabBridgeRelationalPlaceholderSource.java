package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BuiltInNeoForgeTabBridgeRelationalPlaceholderSource implements NeoForgeTabBridgeRelationalPlaceholderSource {
	private static final String REL_PLAYER_NAME = "%rel_luna_player_name%";
	private static final String REL_PLAYER_GROUP_NAME = "%rel_luna_player_group_name%";
	private static final String REL_PLAYER_GROUP_DISPLAY = "%rel_luna_player_group_display%";
	private static final String REL_PLAYER_PREFIX = "%rel_luna_player_prefix%";
	private static final String REL_PLAYER_SUFFIX = "%rel_luna_player_suffix%";
	private static final String REL_PLAYER_DISPLAY = "%rel_luna_player_display%";
	private static final String DEFAULT_DISPLAY_TEMPLATE = "%player_prefix% %displayname%";

	private final MinecraftServer server;
	private final PermissionService permissionService;

	public BuiltInNeoForgeTabBridgeRelationalPlaceholderSource(MinecraftServer server, PermissionService permissionService) {
		this.server = server;
		this.permissionService = permissionService;
	}

	@Override
	public Map<String, Map<String, String>> resolve(ServerPlayer viewer) {
		if (viewer == null || server == null) {
			return Map.of();
		}

		Map<String, String> names = new LinkedHashMap<>();
		Map<String, String> groupNames = new LinkedHashMap<>();
		Map<String, String> groupDisplays = new LinkedHashMap<>();
		Map<String, String> prefixes = new LinkedHashMap<>();
		Map<String, String> suffixes = new LinkedHashMap<>();
		Map<String, String> displays = new LinkedHashMap<>();

		for (ServerPlayer target : server.getPlayerList().getPlayers()) {
			if (target == null) {
				continue;
			}

			String targetName = safe(target.getGameProfile().getName());
			if (targetName.isBlank()) {
				continue;
			}

			UUID targetId = target.getUUID();
			String prefix = resolvePlayerPrefix(targetId);
			String suffix = resolvePlayerSuffix(targetId);
			String displayName = formatDisplay(targetName, prefix, suffix);

			names.put(targetName, targetName);
			groupNames.put(targetName, resolveGroupName(targetId));
			groupDisplays.put(targetName, resolveGroupDisplay(targetId));
			prefixes.put(targetName, prefix);
			suffixes.put(targetName, suffix);
			displays.put(targetName, displayName);
		}

		Map<String, Map<String, String>> values = new LinkedHashMap<>();
		put(values, REL_PLAYER_NAME, names);
		put(values, REL_PLAYER_GROUP_NAME, groupNames);
		put(values, REL_PLAYER_GROUP_DISPLAY, groupDisplays);
		put(values, REL_PLAYER_PREFIX, prefixes);
		put(values, REL_PLAYER_SUFFIX, suffixes);
		put(values, REL_PLAYER_DISPLAY, displays);
		return values;
	}

	private void put(Map<String, Map<String, String>> output, String identifier, Map<String, String> values) {
		if (identifier == null || identifier.isBlank() || values == null || values.isEmpty()) {
			return;
		}

		output.put(identifier, Map.copyOf(values));
	}

	private String resolveGroupName(UUID playerId) {
		if (playerId == null || permissionService == null) {
			return "";
		}

		return safe(permissionService.getGroupName(playerId));
	}

	private String resolveGroupDisplay(UUID playerId) {
		if (playerId == null || permissionService == null) {
			return "";
		}

		return safe(permissionService.getGroupDisplayName(playerId));
	}

	private String resolvePlayerPrefix(UUID playerId) {
		if (playerId == null || permissionService == null) {
			return "";
		}

		return safe(permissionService.getPlayerPrefix(playerId));
	}

	private String resolvePlayerSuffix(UUID playerId) {
		if (playerId == null || permissionService == null) {
			return "";
		}

		return safe(permissionService.getPlayerSuffix(playerId));
	}

	private String formatDisplay(String playerName, String prefix, String suffix) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("player_prefix", safe(prefix));
		values.put("player_suffix", safe(suffix));
		values.put("displayname", safe(playerName));
		String rendered = DEFAULT_DISPLAY_TEMPLATE;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			rendered = rendered.replace("%" + entry.getKey() + "%", entry.getValue());
		}

		String normalized = rendered.trim();
		return normalized.isEmpty() ? safe(playerName) : normalized;
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}