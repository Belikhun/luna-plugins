package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.regex.Matcher;

final class NeoForgeImportedPlaceholderProvider implements NeoForgePlaceholderProvider {
	@Override
	public String resolveLunaValue(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawKey,
		String normalizedKey,
		NeoForgePlaceholderSnapshot snapshot
	) {
		String expandedKey = replaceInnerPlaceholders(support, player, rawKey, snapshot);
		String expandedNormalized = expandedKey.toLowerCase(Locale.ROOT);

		Matcher worldWeatherMatcher = BuiltInNeoForgePlaceholderService.WORLD_WEATHER_PATTERN.matcher(expandedKey);
		if (worldWeatherMatcher.matches()) {
			String worldName = worldWeatherMatcher.group(1);
			ServerLevel level = support.findLevel(worldName);
			if (level == null) {
				return "unknown:" + worldName;
			}

			boolean raining = level.isRaining();
			boolean thundering = level.isThundering();
			return switch (worldWeatherMatcher.group(2).toLowerCase(Locale.ROOT)) {
				case "weather" -> LunaImportedPlaceholderSupport.weatherText(raining, thundering);
				case "weathericon" -> LunaImportedPlaceholderSupport.weatherIcon(raining, thundering);
				case "weathercolor" -> LunaImportedPlaceholderSupport.weatherColor(raining, thundering);
				case "weatherduration" -> LunaImportedPlaceholderSupport.formatDurationSeconds(Math.floorDiv(support.currentWeatherDurationTicks(level, raining, thundering), 20L));
				default -> null;
			};
		}

		if (expandedNormalized.equals("voicechat_status")) {
			return LunaImportedPlaceholderSupport.voiceChatStatus(SimpleVoiceChatCompat.playerStatus(player.getUUID()));
		}

		if (expandedNormalized.equals("voicechat_group")) {
			return SimpleVoiceChatCompat.playerGroup(player.getUUID());
		}

		if (expandedNormalized.equals("player_level")) {
			return LunaImportedPlaceholderSupport.playerLevel(player.experienceLevel);
		}

		Matcher playerStatusMatcher = BuiltInNeoForgePlaceholderService.PLAYER_STATUS_PATTERN.matcher(expandedKey);
		if (playerStatusMatcher.matches()) {
			ServerLevel level = player.serverLevel();
			if (level == null) {
				return "<white>❌<reset>";
			}
			return LunaImportedPlaceholderSupport.playerStatusDot(support.toWorldKind(level), playerStatusMatcher.group(1));
		}

		Matcher stripColorMatcher = BuiltInNeoForgePlaceholderService.STRIP_COLOR_PATTERN.matcher(expandedKey);
		if (stripColorMatcher.matches()) {
			return switch (stripColorMatcher.group(1).toLowerCase(Locale.ROOT)) {
				case "legacy" -> LunaImportedPlaceholderSupport.stripLegacyColors(stripColorMatcher.group(2));
				case "mm" -> LunaImportedPlaceholderSupport.stripMiniMessage(stripColorMatcher.group(2));
				default -> null;
			};
		}

		Matcher mm2lMatcher = BuiltInNeoForgePlaceholderService.MM2L_PATTERN.matcher(expandedKey);
		if (mm2lMatcher.matches()) {
			return LunaImportedPlaceholderSupport.miniMessageToLegacy(mm2lMatcher.group(1));
		}

		return rawKey.equals(expandedKey) ? null : resolveLunaValue(support, player, expandedKey, expandedKey.toLowerCase(Locale.ROOT), snapshot);
	}

	private String replaceInnerPlaceholders(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String value,
		NeoForgePlaceholderSnapshot snapshot
	) {
		if (value == null || value.isBlank()) {
			return "";
		}

		String resolved = value;
		for (int depth = 0; depth < 8; depth++) {
			Matcher matcher = BuiltInNeoForgePlaceholderService.BRACKET_PATTERN.matcher(resolved);
			StringBuffer buffer = new StringBuffer();
			boolean changed = false;

			while (matcher.find()) {
				String token = matcher.group(1);
				String replacement = support.resolveRequestedValue(player, token, snapshot);
				if (replacement == null) {
					matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
					continue;
				}
				matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
				changed = true;
			}

			matcher.appendTail(buffer);
			if (!changed) {
				return resolved;
			}
			resolved = buffer.toString();
		}

		return resolved;
	}
}
