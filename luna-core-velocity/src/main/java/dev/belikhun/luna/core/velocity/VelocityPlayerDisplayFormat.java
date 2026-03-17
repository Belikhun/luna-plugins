package dev.belikhun.luna.core.velocity;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class VelocityPlayerDisplayFormat {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private static final String DEFAULT_TEMPLATE = "%player_prefix% %displayname%";

	private final String template;
	private final LuckPermsService luckPermsService;
	private volatile boolean miniPlaceholdersEnabled = true;

	public VelocityPlayerDisplayFormat(String template, LuckPermsService luckPermsService) {
		this.template = normalize(template, DEFAULT_TEMPLATE);
		this.luckPermsService = luckPermsService;
	}

	public static VelocityPlayerDisplayFormat fromConfig(Map<String, Object> rootConfig, LuckPermsService luckPermsService) {
		Map<String, Object> strings = ConfigValues.map(rootConfig, "strings");
		return new VelocityPlayerDisplayFormat(
			ConfigValues.stringPreserveWhitespace(strings.get("user-display-format"), DEFAULT_TEMPLATE),
			luckPermsService
		);
	}

	public String template() {
		return template;
	}

	public String playerName(Player player) {
		return player == null ? "" : player.getUsername();
	}

	public String playerName(String playerName) {
		return playerName == null ? "" : playerName;
	}

	public String playerGroupName(Player player) {
		return player == null ? "" : playerGroupName(player.getUniqueId());
	}

	public String playerGroupName(UUID playerId) {
		if (playerId == null || luckPermsService == null) {
			return "";
		}

		return normalizeValue(luckPermsService.getGroupName(playerId));
	}

	public String playerGroupDisplay(Player player) {
		return player == null ? "" : playerGroupDisplay(player.getUniqueId());
	}

	public String playerGroupDisplay(UUID playerId) {
		if (playerId == null || luckPermsService == null) {
			return "";
		}

		return normalizeValue(luckPermsService.getGroupDisplayName(playerId));
	}

	public String playerPrefix(Player player) {
		return player == null ? "" : playerPrefix(player.getUniqueId());
	}

	public String playerPrefix(UUID playerId) {
		if (playerId == null || luckPermsService == null) {
			return "";
		}

		return normalizeValue(luckPermsService.getPlayerPrefix(playerId));
	}

	public String playerSuffix(Player player) {
		return player == null ? "" : playerSuffix(player.getUniqueId());
	}

	public String playerSuffix(UUID playerId) {
		if (playerId == null || luckPermsService == null) {
			return "";
		}

		return normalizeValue(luckPermsService.getPlayerSuffix(playerId));
	}

	public String format(Player player) {
		return format(player, playerName(player), Map.of());
	}

	public String format(Player player, String fallbackDisplayName, Map<String, String> additionalValues) {
		String displayName = fallbackDisplayName == null ? "" : fallbackDisplayName;
		String internalRendered = renderRaw(template, baseValues(player == null ? null : player.getUniqueId(), displayName));
		String miniRendered = resolveMiniPlaceholders(player, internalRendered);
		String rendered = renderRaw(miniRendered, additionalValues == null ? Map.of() : additionalValues);
		String normalized = rendered == null ? "" : rendered.trim();
		return normalized.isEmpty() ? displayName : normalized;
	}

	public String format(UUID playerId, String fallbackDisplayName) {
		String displayName = fallbackDisplayName == null ? "" : fallbackDisplayName;
		String rendered = renderRaw(template, baseValues(playerId, displayName));
		String normalized = rendered == null ? "" : rendered.trim();
		return normalized.isEmpty() ? displayName : normalized;
	}

	private Map<String, String> baseValues(UUID playerId, String displayName) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("player_prefix", playerPrefix(playerId));
		values.put("player_suffix", playerSuffix(playerId));
		values.put("player_group_name", playerGroupName(playerId));
		values.put("player_group_display", playerGroupDisplay(playerId));
		values.put("displayname", displayName == null ? "" : displayName);
		values.put("player_name", displayName == null ? "" : displayName);
		values.put("sender_name", displayName == null ? "" : displayName);
		values.put("target_name", displayName == null ? "" : displayName);
		values.put("receiver_name", displayName == null ? "" : displayName);
		return values;
	}

	private String resolveMiniPlaceholders(Player player, String input) {
		if (!miniPlaceholdersEnabled || input == null || input.isBlank()) {
			return input == null ? "" : input;
		}

		try {
			TagResolver global = MiniPlaceholders.globalPlaceholders();
			TagResolver audience = MiniPlaceholders.audiencePlaceholders();
			Component component = player == null
				? MINI_MESSAGE.deserialize(input, global, audience)
				: MINI_MESSAGE.deserialize(input, player, global, audience);
			return MINI_MESSAGE.serialize(component);
		} catch (NoClassDefFoundError | Exception ignored) {
			miniPlaceholdersEnabled = false;
			return input;
		}
	}

	private String renderRaw(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String percentPlaceholder = "%" + entry.getKey() + "%";
			String replacement = MINI_MESSAGE.escapeTags(entry.getValue() == null ? "" : entry.getValue());
			output = output.replace(percentPlaceholder, replacement);
		}
		return output;
	}

	private String normalizeValue(String value) {
		return value == null ? "" : value;
	}

	private static String normalize(String value, String fallback) {
		if (value == null || value.isEmpty()) {
			return fallback;
		}
		return value;
	}
}
