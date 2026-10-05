package dev.belikhun.luna.tabbridge.mc.runtime;

import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code %rel_luna_*%} values: what every other player looks like from a
 * viewer's seat. Read from the placeholder engine's samples, so a refresh costs
 * a map walk rather than a LuckPerms lookup per pair.
 */
public final class BuiltInTabBridgeRelationalPlaceholderSource implements TabBridgeRelationalPlaceholderSource {
	private static final String REL_PLAYER_NAME = "%rel_luna_player_name%";
	private static final String REL_PLAYER_GROUP_NAME = "%rel_luna_player_group_name%";
	private static final String REL_PLAYER_GROUP_DISPLAY = "%rel_luna_player_group_display%";
	private static final String REL_PLAYER_PREFIX = "%rel_luna_player_prefix%";
	private static final String REL_PLAYER_SUFFIX = "%rel_luna_player_suffix%";
	private static final String REL_PLAYER_DISPLAY = "%rel_luna_player_display%";
	/** The proxy's default display format; a later phase pushes the configured one down. */
	private static final String DEFAULT_DISPLAY_TEMPLATE = "%player_prefix% %displayname%";

	private final MinecraftServer server;
	private final PlaceholderRuntime<ServerPlayer> placeholders;
	private final PermissionService permissionService;

	public BuiltInTabBridgeRelationalPlaceholderSource(MinecraftServer server, PlaceholderRuntime<ServerPlayer> placeholders, PermissionService permissionService) {
		this.server = server;
		this.placeholders = placeholders;
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

			String targetName = target.getName().getString();

			if (targetName.isBlank()) {
				continue;
			}

			PlayerSample sample = placeholders == null ? null : placeholders.engine().viewer(target);
			String prefix = sample != null ? sample.prefix() : permission(target.getUUID(), PermissionFact.PREFIX);
			String suffix = sample != null ? sample.suffix() : permission(target.getUUID(), PermissionFact.SUFFIX);

			names.put(targetName, targetName);
			groupNames.put(targetName, sample != null ? sample.groupName() : permission(target.getUUID(), PermissionFact.GROUP));
			groupDisplays.put(targetName, sample != null ? sample.groupDisplayName() : permission(target.getUUID(), PermissionFact.GROUP_DISPLAY));
			prefixes.put(targetName, prefix);
			suffixes.put(targetName, suffix);
			displays.put(targetName, formatDisplay(targetName, prefix, suffix));
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

	private enum PermissionFact {
		PREFIX,
		SUFFIX,
		GROUP,
		GROUP_DISPLAY
	}

	private String permission(UUID playerId, PermissionFact fact) {
		if (permissionService == null || !permissionService.isAvailable()) {
			return "";
		}

		String value = switch (fact) {
			case PREFIX -> permissionService.getPlayerPrefix(playerId);
			case SUFFIX -> permissionService.getPlayerSuffix(playerId);
			case GROUP -> permissionService.getGroupName(playerId);
			case GROUP_DISPLAY -> permissionService.getGroupDisplayName(playerId);
		};

		return value == null ? "" : value;
	}

	private void put(Map<String, Map<String, String>> output, String identifier, Map<String, String> values) {
		if (values.isEmpty()) {
			return;
		}

		output.put(identifier, Map.copyOf(values));
	}

	private String formatDisplay(String playerName, String prefix, String suffix) {
		String rendered = DEFAULT_DISPLAY_TEMPLATE
			.replace("%player_prefix%", prefix == null ? "" : prefix)
			.replace("%player_suffix%", suffix == null ? "" : suffix)
			.replace("%displayname%", playerName)
			.trim();

		return rendered.isEmpty() ? playerName : rendered;
	}
}
