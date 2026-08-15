package dev.belikhun.luna.core.mc12.placeholder;

import dev.belikhun.luna.core.mc12.placeholder.LegacyPlaceholderService.LegacyPlaceholderProvider;
import dev.belikhun.luna.legacy.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.ui.LunaProgressBarPresets;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The values the core itself publishes: the server, the player and the bars.
 *
 * Keys match the other backends exactly, because a tab list written once is
 * rendered by whichever backend the player happens to be on - a 1.12.2 server
 * answering `%luna_tps%` differently would show up as a row that changes shape
 * when someone walks through a portal.
 */
public final class BuiltinLegacyPlaceholders implements LegacyPlaceholderProvider {
	/** 1.12.2 ticks a day in 24000; the day starts at 06:00 in-game. */
	private static final int TICKS_PER_DAY = 24000;
	private static final int TICKS_PER_HOUR = 1000;
	private static final int DAY_START_HOUR = 6;

	@Override
	public Set<String> namespaces() {
		return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList("", "luna")));
	}

	@Override
	public void contributeSnapshot(
		LegacyPlaceholderService support,
		EntityPlayerMP player,
		PlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
		for (Map.Entry<String, String> entry : coreValues(support, player, snapshot).entrySet()) {
			support.putCore(values, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Every fixed value this provider knows, keyed without its namespace.
	 *
	 * One map serves both callers: the snapshot publishes all of it, and a single
	 * lookup reads one entry out of it. The reference implementation lists these
	 * keys twice - once as a snapshot and once as a switch in its resolver - which
	 * is exactly how `%luna_tps%` can end up published but unresolvable.
	 */
	private Map<String, String> coreValues(
		LegacyPlaceholderService support,
		EntityPlayerMP player,
		PlaceholderSnapshot snapshot
	) {
		Map<String, String> values = new LinkedHashMap<String, String>();
		int ping = snapshot.playerPingMillis();

		values.put("current_server", support.localServerName());
		values.put("status", whitelisted(support) ? "MAINT" : "ONLINE");
		values.put("online", Integer.toString(onlinePlayers(support)));
		values.put("max", Integer.toString(maxPlayers(support)));
		values.put("tps", support.formatTps(snapshot.currentTps()));
		values.put("player_ping", Integer.toString(ping));

		// a backend has no latency to itself; the proxy fills this in for the row it
		// draws, and the key exists here so a shared layout does not go blank
		values.put("latency", "0");

		values.put("uptime", Formatters.compactDuration(Duration.ofMillis(snapshot.uptimeMillis())));
		values.put("uptime_long", Formatters.duration(Duration.ofMillis(snapshot.uptimeMillis())));
		values.put("uptime_ms", Long.toString(snapshot.uptimeMillis()));
		values.put("system_cpu", support.formatPercent(snapshot.systemCpuPercent()));
		values.put("process_cpu", support.formatPercent(snapshot.processCpuPercent()));
		values.put("version", support.safe(gameVersion(support)));
		values.put("display", support.localServerName());
		values.put("server_name", support.localServerName());
		values.put("host_name", support.localServerName());
		values.put("color", LegacyPlaceholderService.DEFAULT_COLOR);
		values.put("whitelist", Boolean.toString(whitelisted(support)));
		values.put("total_entities", Integer.toString(snapshot.totalEntities()));
		values.put("total_living_entities", Integer.toString(snapshot.totalLivingEntities()));
		values.put("total_chunks", Integer.toString(snapshot.totalChunks()));

		values.put("player_name", player.getGameProfile().getName());
		values.put("player_world", worldName(player));
		values.put("player_health", Integer.toString((int) Math.ceil(player.getHealth())));
		values.put("player_food", Integer.toString(player.getFoodStats().getFoodLevel()));
		values.put("player_level", Integer.toString(player.experienceLevel));
		values.put("player_gamemode", player.interactionManager.getGameType().getName());
		values.put("player_x", Integer.toString((int) Math.floor(player.posX)));
		values.put("player_y", Integer.toString((int) Math.floor(player.posY)));
		values.put("player_z", Integer.toString((int) Math.floor(player.posZ)));

		putBars(support, snapshot, values, ping);

		return values;
	}

	private void putBars(
		LegacyPlaceholderService support,
		PlaceholderSnapshot snapshot,
		Map<String, String> values,
		int ping
	) {
		int width = LegacyPlaceholderService.DEFAULT_BAR_WIDTH;

		values.put("tps_bar", support.buildBar(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), width));
		values.put("player_ping_bar", support.buildBar(LunaProgressBarPresets.latency("ping", ping), width));
		values.put("latency_bar", support.buildBar(LunaProgressBarPresets.latency("latency", 0D), width));
		values.put("system_cpu_bar", support.buildBar(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), width));
		values.put("process_cpu_bar", support.buildBar(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), width));
		values.put("ram_bar", support.buildBar(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), width));

		values.put("tps_bar_only", support.buildBarOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), width));
		values.put("player_ping_bar_only", support.buildBarOnly(LunaProgressBarPresets.latency("ping", ping), width));
		values.put("latency_bar_only", support.buildBarOnly(LunaProgressBarPresets.latency("latency", 0D), width));
		values.put("system_cpu_bar_only", support.buildBarOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent()), width));
		values.put("process_cpu_bar_only", support.buildBarOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent()), width));
		values.put("ram_bar_only", support.buildBarOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()), width));

		values.put("tps_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.tps("tps", snapshot.currentTps())));
		values.put("player_ping_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.latency("ping", ping)));
		values.put("latency_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.latency("latency", 0D)));
		values.put("system_cpu_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent())));
		values.put("process_cpu_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent())));
		values.put("ram_bar_value_only", support.buildValueOnly(LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes())));
	}

	/**
	 * The identifiers that are cheaper to compute than to publish.
	 *
	 * Anything varying by argument - a world's time, a bar at a chosen width -
	 * belongs here rather than in the snapshot, or the snapshot would have to
	 * enumerate every width anyone might ask for.
	 */
	@Override
	public String resolve(
		LegacyPlaceholderService support,
		EntityPlayerMP player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		PlaceholderSnapshot snapshot
	) {
		if (Strings.isBlank(normalizedParams)) {
			return null;
		}

		String fixed = coreValues(support, player, snapshot).get(normalizedParams);

		if (fixed != null) {
			return fixed;
		}

		if ("world_time".equals(normalizedParams)) {
			return worldTime(player.world);
		}

		if ("world_day".equals(normalizedParams)) {
			return Long.toString(player.world.getWorldTime() / TICKS_PER_DAY);
		}

		if ("world_weather".equals(normalizedParams)) {
			return weatherOf(player.world);
		}

		if ("tick_duration".equals(normalizedParams)) {
			return String.format(Locale.US, "%.2f", Double.valueOf(snapshot.currentTickDurationMillis()));
		}

		if (normalizedParams.startsWith("tps_bar_")) {
			return support.buildBar(LunaProgressBarPresets.tps("tps", snapshot.currentTps()), widthOf(normalizedParams, "tps_bar_"));
		}

		if (normalizedParams.startsWith("ram_bar_")) {
			return support.buildBar(
				LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()),
				widthOf(normalizedParams, "ram_bar_")
			);
		}

		return null;
	}

	/** A width suffix, or the default when the tail is not a number. */
	private int widthOf(String params, String prefix) {
		try {
			return Integer.parseInt(params.substring(prefix.length()));
		} catch (NumberFormatException notANumber) {
			return LegacyPlaceholderService.DEFAULT_BAR_WIDTH;
		}
	}

	/** The in-game clock, which starts its day at 06:00 rather than midnight. */
	private String worldTime(World world) {
		long time = world.getWorldTime() % TICKS_PER_DAY;
		long hour = ((time / TICKS_PER_HOUR) + DAY_START_HOUR) % 24;
		long minute = ((time % TICKS_PER_HOUR) * 60) / TICKS_PER_HOUR;

		return String.format(Locale.US, "%02d:%02d", Long.valueOf(hour), Long.valueOf(minute));
	}

	private String weatherOf(World world) {
		if (world.isThundering()) {
			return "thunder";
		}

		return world.isRaining() ? "rain" : "clear";
	}

	private String worldName(EntityPlayerMP player) {
		if (player.world == null || player.world.provider == null) {
			return "";
		}

		return player.world.provider.getDimensionType().getName();
	}

	private boolean whitelisted(LegacyPlaceholderService support) {
		return support.probe() != null && support.probe().whitelistEnforced();
	}

	private int onlinePlayers(LegacyPlaceholderService support) {
		return support.probe() == null ? 0 : support.probe().onlinePlayers();
	}

	private int maxPlayers(LegacyPlaceholderService support) {
		return support.probe() == null ? 0 : support.probe().maxPlayers();
	}

	private String gameVersion(LegacyPlaceholderService support) {
		return support.probe() == null ? "" : support.probe().gameVersion();
	}
}
