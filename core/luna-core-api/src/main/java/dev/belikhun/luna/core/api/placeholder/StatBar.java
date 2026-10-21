package dev.belikhun.luna.core.api.placeholder;

import dev.belikhun.luna.core.api.ui.LunaProgressBar;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The statistics that render as a progress bar and the three spellings each
 * one answers to: the bar with its value, the bar alone, the value alone.
 *
 * One table generates every bar spec, so a new metric is one line here and
 * gets all three spellings and the optional width on every platform at once.
 *
 * @param key     the key stem, {@code tps_bar}
 * @param scope   whose value it is
 * @param refresh how often it changes
 * @param bar     builds the bar from a request; built fresh per lookup because
 *                rendering sets its width
 */
public record StatBar(String key, PlaceholderScope scope, PlaceholderRefresh refresh, Function<PlaceholderRequest<?>, LunaProgressBar> bar) {
	/** Bar width when the key gives none. */
	public static final int DEFAULT_WIDTH = 25;
	public static final int MIN_WIDTH = 1;
	public static final int MAX_WIDTH = 120;

	/** The bars luna ships. */
	public static final List<StatBar> ALL = List.of(
		new StatBar("tps_bar", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			request -> LunaProgressBarPresets.tps("tps", request.server().tps())),
		new StatBar("player_ping_bar", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			request -> LunaProgressBarPresets.latency("ping", request.viewer() == null ? 0D : request.viewer().pingMillis())),
		new StatBar("latency_bar", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			request -> LunaProgressBarPresets.latency("latency", request.server().heartbeatLatencyMillis())),
		new StatBar("system_cpu_bar", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			request -> LunaProgressBarPresets.cpu("sys<gray>%</gray>", request.server().systemCpuPercent())),
		new StatBar("process_cpu_bar", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			request -> LunaProgressBarPresets.cpu("proc<gray>%</gray>", request.server().processCpuPercent())),
		new StatBar("ram_bar", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			request -> LunaProgressBarPresets.ram("ram", request.server().ramUsedBytes(), request.server().ramMaxBytes()))
	);

	/** Clamps a requested width into the supported range. */
	public static int clampWidth(int width) {
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
	}

	/**
	 * The three specs for this bar under a namespace.
	 *
	 * @param namespace the namespace to register them in
	 * @param <P>       the platform's player type
	 * @return {@code key[_<width>]}, {@code key_only[_<width>]}, their
	 *         {@code …_glyph_<glyph>[_<width>]} twins drawing the bar with a
	 *         glyph of the writer's choosing, and {@code key_value_only}
	 */
	public <P> List<PlaceholderSpec<P>> specs(String namespace) {
		List<PlaceholderSpec<P>> specs = new ArrayList<>();
		String metric = key.substring(0, key.length() - "_bar".length());

		specs.add(PlaceholderSpec.of(namespace, key + "[_<width:width>]", scope, refresh,
			"Progress bar for " + metric + " with its label and value; width 1..120, default 25",
			request -> render(request).width(width(request)).render()));

		specs.add(PlaceholderSpec.of(namespace, key + "_only[_<width:width>]", scope, refresh,
			"Progress bar for " + metric + " without label or value",
			request -> render(request).width(width(request)).renderBar()));

		// a server whose resource pack replaces the default font can draw the
		// block glyph far wider than a cell; these let its config pick another
		specs.add(PlaceholderSpec.of(namespace, key + "_glyph_<glyph:word>[_<width:width>]", scope, refresh,
			"Progress bar for " + metric + " with its label and value, drawn with the given glyph (\\uXXXX escapes)",
			request -> render(request).glyph(glyph(request)).width(width(request)).render()));

		specs.add(PlaceholderSpec.of(namespace, key + "_only_glyph_<glyph:word>[_<width:width>]", scope, refresh,
			"Progress bar for " + metric + " without label or value, drawn with the given glyph (\\uXXXX escapes)",
			request -> render(request).glyph(glyph(request)).width(width(request)).renderBar()));

		specs.add(PlaceholderSpec.of(namespace, key + "_value_only", scope, refresh,
			"The value text of the " + metric + " bar, coloured by its level",
			request -> render(request).renderValue()));

		return specs;
	}

	private LunaProgressBar render(PlaceholderRequest<?> request) {
		return bar.apply(request);
	}

	private static String glyph(PlaceholderRequest<?> request) {
		return LunaPlaceholders.decodeToken(request.param("glyph"));
	}

	private static int width(PlaceholderRequest<?> request) {
		return clampWidth(request.intParam("width", DEFAULT_WIDTH));
	}
}
