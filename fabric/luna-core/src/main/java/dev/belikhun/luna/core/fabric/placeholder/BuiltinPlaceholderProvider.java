package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.api.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.core.api.string.Formatters;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * The values luna itself publishes: server identity, load, and the player's own
 * position and health.
 *
 * Every one is published twice, bare and under a {@code luna_} prefix, so a
 * template can disambiguate from a same-named placeholder another mod provides.
 */
final class BuiltinPlaceholderProvider implements FabricPlaceholderProvider {
	@Override
	public Set<String> namespaces() {
		return Set.of("", "luna");
	}

	@Override
	public void contributeSnapshot(
		BuiltInFabricPlaceholderService support,
		ServerPlayer player,
		PlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
		for (String key : CORE_KEYS) {
			support.putCore(values, key, resolveLunaValue(support, player, key, key, snapshot));
		}

		// the host name the console knows this backend by, which is not always what
		// the backend calls itself
		support.putLunaAlias(values, "host_name", support.currentServerInfoName());

		for (StatBars bars : StatBars.ALL) {
			support.putCore(values, bars.key(), support.buildBar(bars.bar().apply(snapshot), BuiltInFabricPlaceholderService.DEFAULT_BAR_WIDTH));
			support.putCore(values, bars.key() + StatBars.BAR_ONLY, support.buildBarOnly(bars.bar().apply(snapshot), BuiltInFabricPlaceholderService.DEFAULT_BAR_WIDTH));
			support.putCore(values, bars.key() + StatBars.VALUE_ONLY, support.buildValueOnly(bars.bar().apply(snapshot)));
		}
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
		return "luna".equals(normalizedNamespace)
			? resolveLunaValue(support, player, rawParams, normalizedParams, snapshot)
			: resolveNativeValue(support, player, normalizedParams);
	}

	/**
	 * Every key {@link #resolveLunaValue} answers with no argument of its own.
	 *
	 * Deliberately not including {@code player_status}: that one belongs to the
	 * imported provider, and publishing a blank for it here would be worse than
	 * leaving it out, because a snapshot never re-resolves a key it already holds.
	 */
	private static final Set<String> CORE_KEYS = Set.of(
		"current_server", "status", "online", "max", "tps", "player_ping", "latency",
		"uptime", "uptime_long", "uptime_ms", "system_cpu", "process_cpu", "version",
		"display", "server_name", "color", "whitelist",
		"total_entities", "total_living_entities", "total_chunks"
	);

	private String resolveLunaValue(
		BuiltInFabricPlaceholderService support,
		ServerPlayer player,
		String rawKey,
		String normalizedKey,
		PlaceholderSnapshot snapshot
	) {
		String value = switch (normalizedKey) {
			case "current_server", "display" -> support.localServerName();
			case "status" -> support.whitelistEnforced() ? "MAINT" : "ONLINE";
			case "online" -> Integer.toString(support.onlinePlayers());
			case "max" -> Integer.toString(support.maxPlayers());
			case "tps" -> support.formatTps(snapshot.currentTps());
			case "player_ping" -> Integer.toString(snapshot.playerPingMillis());
			case "latency" -> "0";
			case "uptime" -> Formatters.compactDuration(Duration.ofMillis(snapshot.uptimeMillis()));
			case "uptime_long" -> Formatters.duration(Duration.ofMillis(snapshot.uptimeMillis()));
			case "uptime_ms" -> Long.toString(snapshot.uptimeMillis());
			case "system_cpu" -> support.formatPercent(snapshot.systemCpuPercent());
			case "process_cpu" -> support.formatPercent(snapshot.processCpuPercent());
			case "version" -> support.serverVersion();
			case "host_name", "server_name" -> support.currentServerInfoName();
			case "color" -> BuiltInFabricPlaceholderService.DEFAULT_COLOR;
			case "whitelist" -> Boolean.toString(support.whitelistEnforced());
			case "total_entities" -> Integer.toString(snapshot.totalEntities());
			case "total_living_entities" -> Integer.toString(snapshot.totalLivingEntities());
			case "total_chunks" -> Integer.toString(snapshot.totalChunks());
			default -> null;
		};

		if (value != null) {
			return value;
		}

		return resolveBar(support, normalizedKey, snapshot);
	}

	private String resolveBar(BuiltInFabricPlaceholderService support, String normalizedKey, PlaceholderSnapshot snapshot) {
		for (StatBars bars : StatBars.ALL) {
			// the plain key also accepts a width suffix, which is why it is tried
			// before the two suffixed forms rather than after
			String value = support.resolveCurrentBar(normalizedKey, bars.key(),
				width -> support.buildBar(bars.bar().apply(snapshot), width));

			if (value != null) {
				return value;
			}

			value = support.resolveCurrentBar(normalizedKey, bars.key() + StatBars.BAR_ONLY,
				width -> support.buildBarOnly(bars.bar().apply(snapshot), width));

			if (value != null) {
				return value;
			}

			value = support.resolveExact(normalizedKey, bars.key() + StatBars.VALUE_ONLY,
				() -> support.buildValueOnly(bars.bar().apply(snapshot)));

			if (value != null) {
				return value;
			}
		}

		return null;
	}

	private String resolveNativeValue(BuiltInFabricPlaceholderService support, ServerPlayer player, String normalizedIdentifier) {
		return switch (normalizedIdentifier) {
			case "player_displayname" -> support.safe(player.getDisplayName().getString());
			case "world" -> support.currentWorldName(player);
			case "world_time" -> support.currentWorldTime(player);
			case "player_health" -> support.formatDecimal(Math.max(0D, player.getHealth()));
			case "player_health_rounded" -> Integer.toString(Math.max(0, Math.round(player.getHealth())));
			case "player_max_health" -> support.formatDecimal(Math.max(0D, player.getMaxHealth()));
			case "player_max_health_rounded" -> Integer.toString(Math.max(0, Math.round(player.getMaxHealth())));
			case "player_x" -> Integer.toString(player.getBlockX());
			case "player_y" -> Integer.toString(player.getBlockY());
			case "player_z" -> Integer.toString(player.getBlockZ());
			case "player_biome" -> support.currentBiomeName(player);
			case "server_name" -> support.currentServerInfoName();
			default -> null;
		};
	}
}
