package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.api.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;

import java.util.List;
import java.util.function.Function;

/**
 * The statistics that render as a progress bar, and the three spellings each one
 * answers to.
 *
 * A bar is built fresh per lookup because rendering it sets its width, so a
 * shared instance would carry the last caller's.
 */
record StatBars(String key, Function<PlaceholderSnapshot, LunaProgressBar> bar) {
	/** Suffix for the bar without its trailing value. */
	static final String BAR_ONLY = "_only";

	/** Suffix for the value without its bar. */
	static final String VALUE_ONLY = "_value_only";

	static final List<StatBars> ALL = List.of(
		new StatBars("tps_bar", snapshot -> LunaProgressBarPresets.tps("tps", snapshot.currentTps())),
		new StatBars("player_ping_bar", snapshot -> LunaProgressBarPresets.latency("ping", snapshot.playerPingMillis())),

		// the proxy leg of a player's latency, which a backend cannot see; the key
		// exists so one template serves both sides of the network
		new StatBars("latency_bar", snapshot -> LunaProgressBarPresets.latency("latency", 0D)),

		new StatBars("system_cpu_bar", snapshot -> LunaProgressBarPresets.cpu("sys<gray>%</gray>", snapshot.systemCpuPercent())),
		new StatBars("process_cpu_bar", snapshot -> LunaProgressBarPresets.cpu("proc<gray>%</gray>", snapshot.processCpuPercent())),
		new StatBars("ram_bar", snapshot -> LunaProgressBarPresets.ram("ram", snapshot.ramUsedBytes(), snapshot.ramMaxBytes()))
	);
}
