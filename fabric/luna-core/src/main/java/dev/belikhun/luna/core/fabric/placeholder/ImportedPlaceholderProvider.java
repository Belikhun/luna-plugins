package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.core.fabric.compat.WorldFacts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The placeholders luna brought over from the Paper side, so one proxy-side
 * template renders the same whichever software a backend runs.
 *
 * These take arguments inside the identifier - a world name, a colour mode, a
 * whole nested placeholder in braces - which is why this provider expands
 * {@code {…}} before matching anything.
 */
final class ImportedPlaceholderProvider implements FabricPlaceholderProvider {
	/** Enough passes for a placeholder inside a placeholder inside a placeholder. */
	private static final int MAX_NESTING_DEPTH = 8;

	@Override
	public Set<String> namespaces() {
		return Set.of("luna");
	}

	@Override
	public String resolve(
		BuiltInFabricPlaceholderService support,
		ServerPlayer player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		PlaceholderSnapshot snapshot
	) {
		return resolveLunaParams(support, player, rawParams, snapshot);
	}

	private String resolveLunaParams(
		BuiltInFabricPlaceholderService support,
		ServerPlayer player,
		String rawKey,
		PlaceholderSnapshot snapshot
	) {
		String expandedKey = replaceInnerPlaceholders(support, player, rawKey, snapshot);
		String expandedNormalized = expandedKey.toLowerCase(Locale.ROOT);

		Matcher worldWeatherMatcher = BuiltInFabricPlaceholderService.WORLD_WEATHER_PATTERN.matcher(expandedKey);

		if (worldWeatherMatcher.matches()) {
			return resolveWorldWeather(support, worldWeatherMatcher);
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

		Matcher playerStatusMatcher = BuiltInFabricPlaceholderService.PLAYER_STATUS_PATTERN.matcher(expandedKey);

		if (playerStatusMatcher.matches()) {
			ServerLevel level = WorldFacts.levelOf(player);

			if (level == null) {
				return "<white>❌<reset>";
			}

			return LunaImportedPlaceholderSupport.playerStatusDot(support.toWorldKind(level), playerStatusMatcher.group(1));
		}

		Matcher stripColorMatcher = BuiltInFabricPlaceholderService.STRIP_COLOR_PATTERN.matcher(expandedKey);

		if (stripColorMatcher.matches()) {
			return switch (stripColorMatcher.group(1).toLowerCase(Locale.ROOT)) {
				case "legacy" -> LunaImportedPlaceholderSupport.stripLegacyColors(stripColorMatcher.group(2));
				case "mm" -> LunaImportedPlaceholderSupport.stripMiniMessage(stripColorMatcher.group(2));
				default -> null;
			};
		}

		Matcher mm2lMatcher = BuiltInFabricPlaceholderService.MM2L_PATTERN.matcher(expandedKey);

		if (mm2lMatcher.matches()) {
			return LunaImportedPlaceholderSupport.miniMessageToLegacy(mm2lMatcher.group(1));
		}

		// expanding the braces may have produced a key that now matches something;
		// only worth another pass if the expansion actually changed anything
		if (rawKey.equals(expandedKey)) {
			return null;
		}

		return resolveLunaParams(support, player, expandedKey, snapshot);
	}

	private String resolveWorldWeather(BuiltInFabricPlaceholderService support, Matcher matcher) {
		String worldName = matcher.group(1);
		ServerLevel level = support.findLevel(worldName);

		if (level == null) {
			return "unknown:" + worldName;
		}

		boolean raining = level.isRaining();
		boolean thundering = level.isThundering();

		return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
			case "weather" -> LunaImportedPlaceholderSupport.weatherText(raining, thundering);
			case "weathericon" -> LunaImportedPlaceholderSupport.weatherIcon(raining, thundering);
			case "weathercolor" -> LunaImportedPlaceholderSupport.weatherColor(raining, thundering);
			case "weatherduration" -> LunaImportedPlaceholderSupport.formatDurationSeconds(
				Math.floorDiv(support.currentWeatherDurationTicks(level, raining, thundering), 20L)
			);
			default -> null;
		};
	}

	private String replaceInnerPlaceholders(
		BuiltInFabricPlaceholderService support,
		ServerPlayer player,
		String value,
		PlaceholderSnapshot snapshot
	) {
		if (value == null || value.isBlank()) {
			return "";
		}

		String resolved = value;

		for (int depth = 0; depth < MAX_NESTING_DEPTH; depth++) {
			Matcher matcher = BuiltInFabricPlaceholderService.BRACKET_PATTERN.matcher(resolved);
			StringBuilder buffer = new StringBuilder();
			boolean changed = false;

			while (matcher.find()) {
				String replacement = support.resolveRequestedValue(player, matcher.group(1), snapshot);

				// an unresolvable token is left exactly as written, braces and all:
				// blanking it would hide the typo that caused it
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
