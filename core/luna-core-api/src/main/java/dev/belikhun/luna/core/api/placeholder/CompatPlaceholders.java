package dev.belikhun.luna.core.api.placeholder;

import dev.belikhun.luna.core.api.heartbeat.SparkMetrics;
import dev.belikhun.luna.core.api.heartbeat.TickDurationFormat;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The placeholders a mod-loader backend answers in place of the PlaceholderAPI
 * expansions a Paper server would have: {@code player}, {@code world},
 * {@code server}, {@code luckperms}, {@code vault} and {@code spark}.
 *
 * Paper never registers these; the real expansions are installed there and
 * would collide. The values are shaped like the expansions they stand in for,
 * so one TAB config serves both kinds of backend.
 */
public final class CompatPlaceholders {
	private static final DateTimeFormatter WORLD_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final long TICKS_PER_DAY = 24_000L;
	private static final int MINUTES_PER_DAY = 1_440;
	private static final int DAY_START_MINUTES = 360;

	private CompatPlaceholders() {
	}

	/**
	 * Every compat spec.
	 *
	 * @param <P> the platform's player type
	 */
	public static <P> List<PlaceholderSpec<P>> specs() {
		List<PlaceholderSpec<P>> specs = new ArrayList<>();

		player(specs, "name", "The viewer's account name", request -> request.viewer().name());
		player(specs, "displayname", "The viewer's display name", request -> request.viewer().displayName());
		player(specs, "ping", "The viewer's ping in ms", request -> Integer.toString(request.viewer().pingMillis()));
		player(specs, "world", "The viewer's world", request -> request.viewer().worldName());
		player(specs, "biome", "The viewer's biome", request -> request.viewer().biome());
		player(specs, "x", "The viewer's block X", request -> Integer.toString(request.viewer().blockX()));
		player(specs, "y", "The viewer's block Y", request -> Integer.toString(request.viewer().blockY()));
		player(specs, "z", "The viewer's block Z", request -> Integer.toString(request.viewer().blockZ()));
		player(specs, "health", "The viewer's health", request -> decimal(request.viewer().health()));
		player(specs, "health_rounded", "The viewer's health, rounded", request -> Integer.toString((int) Math.round(request.viewer().health())));
		player(specs, "max_health", "The viewer's max health", request -> decimal(request.viewer().maxHealth()));
		player(specs, "max_health_rounded", "The viewer's max health, rounded", request -> Integer.toString((int) Math.round(request.viewer().maxHealth())));
		player(specs, "level", "The viewer's experience level", request -> Integer.toString(request.viewer().level()));
		player(specs, "food_level", "The viewer's food level", request -> Integer.toString(request.viewer().foodLevel()));
		player(specs, "gamemode", "The viewer's game mode", request -> request.viewer().gameMode());

		specs.add(PlaceholderSpec.of("world", "", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			"The viewer's world", request -> request.viewer() == null ? null : request.viewer().worldName()));
		specs.add(PlaceholderSpec.of("world", "time", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			"Clock time in the viewer's world", request -> {
				WorldWeather world = request.viewer() == null ? null : request.server().world(request.viewer().worldName());
				return world == null ? null : worldClock(world.dayTimeTicks());
			}));

		specs.add(PlaceholderSpec.of("server", "name", PlaceholderScope.SERVER, PlaceholderRefresh.STATIC,
			"The name the proxy routes to this backend by", request -> request.server().hostName()));
		specs.add(PlaceholderSpec.of("server", "online", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Players online", request -> Integer.toString(request.server().onlinePlayers())));
		specs.add(PlaceholderSpec.of("server", "max_players", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Player slots", request -> Integer.toString(request.server().maxPlayers())));
		specs.add(PlaceholderSpec.of("server", "tps", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Ticks per second", request -> String.format(Locale.US, "%.2f", Math.max(0D, request.server().tps()))));
		specs.add(PlaceholderSpec.of("server", "uptime", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Uptime", request -> dev.belikhun.luna.core.api.string.Formatters.compactDuration(java.time.Duration.ofMillis(Math.max(0L, request.server().uptimeMillis())))));
		specs.add(PlaceholderSpec.of("server", "total_entities", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Entities across all worlds", request -> Integer.toString(request.server().totalEntities())));
		specs.add(PlaceholderSpec.of("server", "total_living_entities", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Living entities across all worlds", request -> Integer.toString(request.server().totalLivingEntities())));
		specs.add(PlaceholderSpec.of("server", "total_chunks", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"Loaded chunks across all worlds", request -> Integer.toString(request.server().totalChunks())));
		specs.add(PlaceholderSpec.of("server", "time_<pattern:rest>", PlaceholderScope.SERVER, PlaceholderRefresh.LIVE,
			"Wall-clock time in a DateTimeFormatter pattern", request -> {
				try {
					return ZonedDateTime.now().format(DateTimeFormatter.ofPattern(request.param("pattern", "HH:mm:ss"), Locale.ROOT));
				} catch (IllegalArgumentException exception) {
					return null;
				}
			}));

		permission(specs, "luckperms", "prefix", request -> request.viewer().prefix());
		permission(specs, "luckperms", "suffix", request -> request.viewer().suffix());
		permission(specs, "luckperms", "primary_group_name", request -> request.viewer().groupName());
		permission(specs, "luckperms", "primary_group_display_name", request -> request.viewer().groupDisplayName());
		permission(specs, "vault", "prefix", request -> request.viewer().prefix());
		permission(specs, "vault", "suffix", request -> request.viewer().suffix());
		permission(specs, "vault", "primary_group", request -> request.viewer().groupName());

		specs.add(PlaceholderSpec.of("spark", "<key:rest>", PlaceholderScope.SERVER, PlaceholderRefresh.SERVER,
			"spark's own placeholders, or luna's tick reading when spark is absent", request -> {
				String key = request.param("key", "");
				String value = SparkMetrics.resolveLegacyPlaceholder(key.toLowerCase(Locale.ROOT));

				if (value != null && !value.isBlank()) {
					return value;
				}

				if (key.toLowerCase(Locale.ROOT).startsWith("tickduration")) {
					double duration = request.server().tickDurationMillis();
					return TickDurationFormat.spread(duration, duration, duration, duration);
				}

				return null;
			}));

		return specs;
	}

	private static <P> void player(List<PlaceholderSpec<P>> specs, String key, String description, PlaceholderResolver<P> resolver) {
		specs.add(PlaceholderSpec.of("player", key, PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER, description,
			request -> request.viewer() == null ? null : resolver.resolve(request)));
	}

	private static <P> void permission(List<PlaceholderSpec<P>> specs, String namespace, String key, PlaceholderResolver<P> resolver) {
		specs.add(PlaceholderSpec.of(namespace, key, PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			"The viewer's " + key.replace('_', ' ') + " from LuckPerms",
			request -> request.viewer() == null ? null : resolver.resolve(request)));
	}

	private static String decimal(double value) {
		String text = String.format(Locale.US, "%.2f", Math.max(0D, value));

		while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
			text = text.substring(0, text.length() - 1);
		}

		return text;
	}

	/** A world's day time as a wall clock, 06:00 at tick zero as the game has it. */
	static String worldClock(long dayTicks) {
		long ticks = Math.floorMod(dayTicks, TICKS_PER_DAY);
		int totalMinutes = (int) ((ticks * MINUTES_PER_DAY) / TICKS_PER_DAY);
		totalMinutes = Math.floorMod(totalMinutes + DAY_START_MINUTES, MINUTES_PER_DAY);
		return WORLD_TIME.format(LocalTime.of(totalMinutes / 60, totalMinutes % 60));
	}
}
