package dev.belikhun.luna.core.api.placeholder;

import dev.belikhun.luna.core.api.string.Formatters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code luna} namespace: what a backend says about itself and about the
 * viewer, the same on every platform.
 *
 * Where the four old engines disagreed, Paper's behaviour is kept, because the
 * production configs were written against it: CPU values carry no percent
 * sign, an absent viewer renders the crossed-out glyph, and {@code latency} is
 * the heartbeat round trip.
 */
public final class LunaPlaceholders {
	/** The namespace for a backend's own facts. */
	public static final String NAMESPACE = "luna";
	private static final String NO_VIEWER_GLYPH = "<white>❌<reset>";
	private static final String NO_VOICE_GLYPH = "<white>⛔<reset>";

	private LunaPlaceholders() {
	}

	/**
	 * Every built-in spec of the {@code luna} namespace.
	 *
	 * @param <P> the platform's player type
	 */
	public static <P> List<PlaceholderSpec<P>> specs() {
		List<PlaceholderSpec<P>> specs = new ArrayList<>();

		server(specs, "current_server", PlaceholderRefresh.STATIC, "This backend's luna name", request -> request.server().backendName());
		server(specs, "host_name", PlaceholderRefresh.STATIC, "The name the proxy routes to this backend by", request -> request.server().hostName());
		server(specs, "server_name", PlaceholderRefresh.STATIC, "Alias of host_name", request -> request.server().hostName());
		server(specs, "display", PlaceholderRefresh.SERVER, "Display name from the proxy's server list", request -> request.server().displayName());
		server(specs, "color", PlaceholderRefresh.SERVER, "Accent colour from the proxy's server list", request -> request.server().accentColor());
		server(specs, "version", PlaceholderRefresh.STATIC, "Server software version", request -> request.server().version());
		server(specs, "status", PlaceholderRefresh.SERVER, "ONLINE, or MAINT while the whitelist is on", request -> request.server().whitelistEnabled() ? "MAINT" : "ONLINE");
		server(specs, "whitelist", PlaceholderRefresh.SERVER, "true or false", request -> Boolean.toString(request.server().whitelistEnabled()));
		server(specs, "online", PlaceholderRefresh.SERVER, "Players online on this backend", request -> Integer.toString(request.server().onlinePlayers()));
		server(specs, "max", PlaceholderRefresh.SERVER, "Player slots", request -> Integer.toString(request.server().maxPlayers()));
		server(specs, "tps", PlaceholderRefresh.SERVER, "Ticks per second, two decimals", request -> format2(request.server().tps()));
		server(specs, "tick_duration", PlaceholderRefresh.SERVER, "Mean tick duration in ms, two decimals", request -> format2(request.server().tickDurationMillis()));
		server(specs, "latency", PlaceholderRefresh.SERVER, "Heartbeat round trip to the proxy in ms", request -> Long.toString(Math.max(0L, request.server().heartbeatLatencyMillis())));
		server(specs, "uptime", PlaceholderRefresh.SERVER, "Uptime, compact", request -> Formatters.compactDuration(Duration.ofMillis(Math.max(0L, request.server().uptimeMillis()))));
		server(specs, "uptime_long", PlaceholderRefresh.SERVER, "Uptime, long form", request -> Formatters.duration(Duration.ofMillis(Math.max(0L, request.server().uptimeMillis()))));
		server(specs, "uptime_ms", PlaceholderRefresh.SERVER, "Uptime in milliseconds", request -> Long.toString(Math.max(0L, request.server().uptimeMillis())));
		server(specs, "system_cpu", PlaceholderRefresh.SERVER, "System CPU percent, one decimal, no sign", request -> formatPercent(request.server().systemCpuPercent()));
		server(specs, "process_cpu", PlaceholderRefresh.SERVER, "Process CPU percent, one decimal, no sign", request -> formatPercent(request.server().processCpuPercent()));
		server(specs, "total_entities", PlaceholderRefresh.SERVER, "Entities across all worlds", request -> Integer.toString(request.server().totalEntities()));
		server(specs, "total_living_entities", PlaceholderRefresh.SERVER, "Living entities across all worlds", request -> Integer.toString(request.server().totalLivingEntities()));
		server(specs, "total_chunks", PlaceholderRefresh.SERVER, "Loaded chunks across all worlds", request -> Integer.toString(request.server().totalChunks()));

		specs.add(PlaceholderSpec.of(NAMESPACE, "world_<world:text>_weather", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Weather of a world as text", request -> weather(request, WeatherPart.TEXT)));
		specs.add(PlaceholderSpec.of(NAMESPACE, "world_<world:text>_weathericon", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Weather of a world as an icon", request -> weather(request, WeatherPart.ICON)));
		specs.add(PlaceholderSpec.of(NAMESPACE, "world_<world:text>_weathercolor", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Colour tag for a world's weather", request -> weather(request, WeatherPart.COLOR)));
		specs.add(PlaceholderSpec.of(NAMESPACE, "world_<world:text>_weatherduration", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Time until a world's weather changes", request -> weather(request, WeatherPart.DURATION)));

		player(specs, "player_ping", PlaceholderRefresh.PLAYER, "The viewer's ping in ms, 0 when absent",
			request -> Integer.toString(request.viewer() == null ? 0 : request.viewer().pingMillis()));
		player(specs, "player_level", PlaceholderRefresh.PLAYER, "The viewer's level, styled",
			request -> LunaImportedPlaceholderSupport.playerLevel(request.viewer() == null ? 0 : request.viewer().level()));
		player(specs, "voicechat_status", PlaceholderRefresh.PLAYER, "Simple Voice Chat state as an icon",
			request -> request.viewer() == null
				? NO_VOICE_GLYPH
				: LunaImportedPlaceholderSupport.voiceChatStatus(request.viewer().voiceChatStatus()));
		player(specs, "voicechat_group", PlaceholderRefresh.PLAYER, "Simple Voice Chat group name, or null",
			request -> request.viewer() == null ? "null" : request.viewer().voiceChatGroup());

		specs.add(PlaceholderSpec.of(NAMESPACE, "player_status[_<dot:rest>]", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			"A glyph coloured by the viewer's dimension; the glyph is the argument",
			request -> request.viewer() == null
				? NO_VIEWER_GLYPH
				: LunaImportedPlaceholderSupport.playerStatusDot(request.viewer().worldKind(), request.param("dot"))));
		specs.add(PlaceholderSpec.of(NAMESPACE, "player_dimension[_<glyph:rest>]", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			"A glyph coloured by the viewer's dimension; the glyph is the argument",
			request -> request.viewer() == null
				? NO_VIEWER_GLYPH
				: LunaImportedPlaceholderSupport.playerDimensionDot(request.viewer().worldKind(), request.param("glyph"))));

		specs.add(PlaceholderSpec.of(NAMESPACE, "stripcolor_legacy_<value:rest>", PlaceholderScope.PLAYER, PlaceholderRefresh.LIVE,
			"The argument with legacy colour codes removed; {…} inside it is expanded first",
			request -> LunaImportedPlaceholderSupport.stripLegacyColors(request.expandedParam("value"))));
		specs.add(PlaceholderSpec.of(NAMESPACE, "stripcolor_mm_<value:rest>", PlaceholderScope.PLAYER, PlaceholderRefresh.LIVE,
			"The argument with MiniMessage tags removed; {…} inside it is expanded first",
			request -> LunaImportedPlaceholderSupport.stripMiniMessage(request.expandedParam("value"))));
		specs.add(PlaceholderSpec.of(NAMESPACE, "mm2l_<value:rest>", PlaceholderScope.PLAYER, PlaceholderRefresh.LIVE,
			"The argument converted from MiniMessage to legacy codes; {…} inside it is expanded first",
			request -> LunaImportedPlaceholderSupport.miniMessageToLegacy(request.expandedParam("value"))));

		for (StatBar bar : StatBar.ALL) {
			specs.addAll(bar.specs(NAMESPACE));
		}

		return specs;
	}

	private static <P> void server(List<PlaceholderSpec<P>> specs, String key, PlaceholderRefresh refresh, String description, PlaceholderResolver<P> resolver) {
		specs.add(PlaceholderSpec.of(NAMESPACE, key, PlaceholderScope.SERVER, refresh, description, resolver));
	}

	private static <P> void player(List<PlaceholderSpec<P>> specs, String key, PlaceholderRefresh refresh, String description, PlaceholderResolver<P> resolver) {
		specs.add(PlaceholderSpec.of(NAMESPACE, key, PlaceholderScope.PLAYER, refresh, description, resolver));
	}

	private enum WeatherPart {
		TEXT,
		ICON,
		COLOR,
		DURATION
	}

	private static String weather(PlaceholderRequest<?> request, WeatherPart part) {
		String worldName = request.expandedParam("world");
		WorldWeather weather = request.server().world(worldName);

		if (weather == null) {
			return "unknown:" + worldName;
		}

		return switch (part) {
			case TEXT -> LunaImportedPlaceholderSupport.weatherText(weather.raining(), weather.thundering());
			case ICON -> LunaImportedPlaceholderSupport.weatherIcon(weather.raining(), weather.thundering());
			case COLOR -> LunaImportedPlaceholderSupport.weatherColor(weather.raining(), weather.thundering());
			case DURATION -> LunaImportedPlaceholderSupport.formatDurationSeconds(Math.floorDiv(Math.max(0L, weather.durationTicks()), 20L));
		};
	}

	private static String format2(double value) {
		return String.format(Locale.US, "%.2f", Double.isNaN(value) || Double.isInfinite(value) ? 0D : Math.max(0D, value));
	}

	private static String formatPercent(double value) {
		double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0D : Math.max(0D, Math.min(100D, value));
		return String.format(Locale.US, "%.1f", safe);
	}
}
