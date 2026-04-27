package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Map;

final class NeoForgeBuiltinPlaceholderProvider implements NeoForgePlaceholderProvider {
	@Override
	public void contributeSnapshot(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		NeoForgePlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
		int playerPingMillis = snapshot.playerPingMillis();
		support.putCore(values, "current_server", support.localServerName());
		support.putCore(values, "status", support.server().isEnforceWhitelist() ? "MAINT" : "ONLINE");
		support.putCore(values, "online", Integer.toString(support.server().getPlayerCount()));
		support.putCore(values, "max", Integer.toString(support.server().getMaxPlayers()));
		support.putCore(values, "tps", support.formatTps(snapshot.currentTps()));
		support.putCore(values, "player_ping", Integer.toString(playerPingMillis));
		support.putCore(values, "latency", "0");
		support.putCore(values, "uptime", Formatters.compactDuration(Duration.ofMillis(snapshot.uptimeMillis())));
		support.putCore(values, "uptime_long", Formatters.duration(Duration.ofMillis(snapshot.uptimeMillis())));
		support.putCore(values, "uptime_ms", Long.toString(snapshot.uptimeMillis()));
		support.putCore(values, "system_cpu", support.formatPercent(snapshot.systemCpuPercent()));
		support.putCore(values, "process_cpu", support.formatPercent(snapshot.processCpuPercent()));
		support.putCore(values, "version", support.safe(support.server().getServerVersion()));
		support.putCore(values, "display", support.localServerName());
		String currentHostName = support.currentServerInfoName();
		support.putCore(values, "server_name", currentHostName);
		support.putLunaAlias(values, "host_name", currentHostName);
		support.putCore(values, "player_status", resolveLunaValue(support, player, "player_status", "player_status", snapshot));
		support.putCore(values, "player_status_⏺", resolveLunaValue(support, player, "player_status_⏺", "player_status_⏺", snapshot));
		support.putCore(values, "color", BuiltInNeoForgePlaceholderService.DEFAULT_COLOR);
		support.putCore(values, "whitelist", Boolean.toString(support.server().isEnforceWhitelist()));
		support.putCore(values, "total_entities", Integer.toString(snapshot.totalEntities()));
		support.putCore(values, "total_living_entities", Integer.toString(snapshot.totalLivingEntities()));
		support.putCore(values, "total_chunks", Integer.toString(snapshot.totalChunks()));

		support.putCore(values, "tps_bar", support.buildBar(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "player_ping_bar", support.buildBar(LunaProgressBarPresets.latency("ping", playerPingMillis), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "latency_bar", support.buildBar(LunaProgressBarPresets.latency("latency", 0D), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "system_cpu_bar", support.buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "process_cpu_bar", support.buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "ram_bar", support.buildBar(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));

		support.putCore(values, "tps_bar_only", support.buildBarOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "player_ping_bar_only", support.buildBarOnly(LunaProgressBarPresets.latency("ping", playerPingMillis), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "latency_bar_only", support.buildBarOnly(LunaProgressBarPresets.latency("latency", 0D), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "system_cpu_bar_only", support.buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "process_cpu_bar_only", support.buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));
		support.putCore(values, "ram_bar_only", support.buildBarOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), BuiltInNeoForgePlaceholderService.DEFAULT_BAR_WIDTH));

		support.putCore(values, "tps_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps())));
		support.putCore(values, "player_ping_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.latency("ping", playerPingMillis)));
		support.putCore(values, "latency_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.latency("latency", 0D)));
		support.putCore(values, "system_cpu_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent())));
		support.putCore(values, "process_cpu_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent())));
		support.putCore(values, "ram_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes())));
	}

	@Override
	public String resolveLunaValue(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawKey,
		String normalizedKey,
		NeoForgePlaceholderSnapshot snapshot
	) {
		String value = switch (normalizedKey) {
			case "current_server" -> support.localServerName();
			case "status" -> support.server().isEnforceWhitelist() ? "MAINT" : "ONLINE";
			case "online" -> Integer.toString(support.server().getPlayerCount());
			case "max" -> Integer.toString(support.server().getMaxPlayers());
			case "tps" -> support.formatTps(snapshot.currentTps());
			case "player_ping" -> Integer.toString(snapshot.playerPingMillis());
			case "latency" -> "0";
			case "uptime" -> Formatters.compactDuration(Duration.ofMillis(snapshot.uptimeMillis()));
			case "uptime_long" -> Formatters.duration(Duration.ofMillis(snapshot.uptimeMillis()));
			case "uptime_ms" -> Long.toString(snapshot.uptimeMillis());
			case "system_cpu" -> support.formatPercent(snapshot.systemCpuPercent());
			case "process_cpu" -> support.formatPercent(snapshot.processCpuPercent());
			case "version" -> support.safe(support.server().getServerVersion());
			case "display" -> support.localServerName();
			case "host_name", "server_name" -> support.currentServerInfoName();
			case "color" -> BuiltInNeoForgePlaceholderService.DEFAULT_COLOR;
			case "whitelist" -> Boolean.toString(support.server().isEnforceWhitelist());
			case "total_entities" -> Integer.toString(snapshot.totalEntities());
			case "total_living_entities" -> Integer.toString(snapshot.totalLivingEntities());
			case "total_chunks" -> Integer.toString(snapshot.totalChunks());
			default -> null;
		};
		if (value != null) {
			return value;
		}

		value = support.resolveCurrentBar(normalizedKey, "tps_bar", width -> support.buildBar(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "player_ping_bar", width -> support.buildBar(LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "latency_bar", width -> support.buildBar(LunaProgressBarPresets.latency("latency", 0D), width));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "system_cpu_bar", width -> support.buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "process_cpu_bar", width -> support.buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "ram_bar", width -> support.buildBar(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), width));
		if (value != null) {
			return value;
		}

		value = support.resolveCurrentBar(normalizedKey, "tps_bar_only", width -> support.buildBarOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveExact(normalizedKey, "tps_bar_value_only", () -> support.buildValueOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps())));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "player_ping_bar_only", width -> support.buildBarOnly(LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveExact(normalizedKey, "player_ping_bar_value_only", () -> support.buildValueOnly(LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis())));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "latency_bar_only", width -> support.buildBarOnly(LunaProgressBarPresets.latency("latency", 0D), width));
		if (value != null) {
			return value;
		}
		value = support.resolveExact(normalizedKey, "latency_bar_value_only", () -> support.buildValueOnly(LunaProgressBarPresets.latency("latency", 0D)));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "system_cpu_bar_only", width -> support.buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveExact(normalizedKey, "system_cpu_bar_value_only", () -> support.buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent())));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "process_cpu_bar_only", width -> support.buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), width));
		if (value != null) {
			return value;
		}
		value = support.resolveExact(normalizedKey, "process_cpu_bar_value_only", () -> support.buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent())));
		if (value != null) {
			return value;
		}
		value = support.resolveCurrentBar(normalizedKey, "ram_bar_only", width -> support.buildBarOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), width));
		if (value != null) {
			return value;
		}
		return support.resolveExact(normalizedKey, "ram_bar_value_only", () -> support.buildValueOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes())));
	}

	@Override
	public String resolveNativeValue(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawIdentifier,
		String normalizedIdentifier,
		NeoForgePlaceholderSnapshot snapshot
	) {
		return switch (normalizedIdentifier) {
			case "player_displayname" -> support.safe(player.getDisplayName().getString());
			case "world" -> support.currentWorldName(player);
			case "world_time" -> support.currentWorldTime(player);
			case "player_health" -> support.formatDecimal(Math.max(0D, player.getHealth()));
			case "player_health_rounded" -> Integer.toString(Math.max(0, Math.round(player.getHealth())));
			case "player_max_health" -> support.formatDecimal(Math.max(0D, player.getMaxHealth()));
			case "player_max_health_rounded" -> Integer.toString(Math.max(0, Math.round((float) player.getMaxHealth())));
			case "player_x" -> Integer.toString(player.getBlockX());
			case "player_y" -> Integer.toString(player.getBlockY());
			case "player_z" -> Integer.toString(player.getBlockZ());
			case "player_biome" -> support.currentBiomeName(player);
			case "server_name" -> support.currentServerInfoName();
			default -> null;
		};
	}
}
