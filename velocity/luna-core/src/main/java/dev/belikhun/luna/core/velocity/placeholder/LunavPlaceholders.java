package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRefresh;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRequest;
import dev.belikhun.luna.core.api.placeholder.PlaceholderScope;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSpec;
import dev.belikhun.luna.core.api.placeholder.ServerSample;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.velocity.VelocityPlayerDisplayFormat;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code lunav} namespace: the network as the proxy sees it.
 *
 * Every {@code server_*} key takes an optional backend argument and falls back
 * to the viewer's current server: {@code %lunav_server_tps_survival%} is
 * survival's TPS, {@code %lunav_server_tps%} is the TPS of wherever the viewer
 * is. Every field the heartbeat carries is reachable this way, so a new
 * heartbeat field is one line here and no new registration anywhere.
 */
public final class LunavPlaceholders {
	/** The namespace for network facts. */
	public static final String NAMESPACE = "lunav";

	private final BackendStatusView statusView;
	private final Supplier<VelocityServerSelectorConfig> selectorConfig;
	private final ServerDisplayResolver displayResolver;
	private final VelocityPlayerDisplayFormat displayFormat;

	/**
	 * @param statusView      the heartbeat registry
	 * @param selectorConfig  the server list, read live so a reload is seen
	 * @param displayResolver display names and accent colours per backend
	 * @param displayFormat   the configured player display format
	 */
	public LunavPlaceholders(
		BackendStatusView statusView,
		Supplier<VelocityServerSelectorConfig> selectorConfig,
		ServerDisplayResolver displayResolver,
		VelocityPlayerDisplayFormat displayFormat
	) {
		this.statusView = statusView;
		this.selectorConfig = selectorConfig;
		this.displayResolver = displayResolver;
		this.displayFormat = displayFormat;
	}

	/** Every built-in {@code lunav} spec. */
	public List<PlaceholderSpec<Player>> specs() {
		List<PlaceholderSpec<Player>> specs = new ArrayList<>();

		network(specs, "online_servers", "Backends currently reporting online", request -> Integer.toString(onlineServers()));
		network(specs, "registered_servers", "Backends in the server list", request -> Integer.toString(selectorConfig.get().servers().size()));
		network(specs, "total_servers", "Alias of registered_servers", request -> Integer.toString(selectorConfig.get().servers().size()));
		network(specs, "total_players", "Players across every backend", request -> Integer.toString(totalPlayers()));

		server(specs, "status", "ONLINE, MAINT or OFFLINE", (status, stats) -> status == null || !status.online() ? "OFFLINE" : (stats != null && stats.whitelistEnabled() ? "MAINT" : "ONLINE"));
		server(specs, "online", "Players online", (status, stats) -> Integer.toString(stats == null ? 0 : Math.max(0, stats.onlinePlayers())));
		server(specs, "max", "Player slots", (status, stats) -> Integer.toString(stats == null ? 0 : Math.max(0, stats.maxPlayers())));
		server(specs, "tps", "Ticks per second, two decimals", (status, stats) -> String.format(Locale.US, "%.2f", stats == null ? 0D : stats.tps()));
		server(specs, "tick_duration", "Mean tick duration in ms", (status, stats) -> String.format(Locale.US, "%.2f", stats == null || stats.ticks() == null ? 0D : stats.ticks().meanMillis()));
		server(specs, "version", "Server software version", (status, stats) -> stats == null || stats.version() == null || stats.version().isBlank() ? "unknown" : stats.version());
		server(specs, "software", "Server software name", (status, stats) -> stats == null || stats.software() == null || stats.software().isBlank() ? "unknown" : stats.software());
		server(specs, "motd", "The backend's MOTD", (status, stats) -> stats == null || stats.motd() == null ? "" : stats.motd());
		server(specs, "whitelist", "true or false", (status, stats) -> Boolean.toString(stats != null && stats.whitelistEnabled()));
		server(specs, "uptime", "Uptime, compact", (status, stats) -> Formatters.compactDuration(Duration.ofMillis(stats == null ? 0L : Math.max(0L, stats.uptimeMillis()))));
		server(specs, "uptime_long", "Uptime, long form", (status, stats) -> Formatters.duration(Duration.ofMillis(stats == null ? 0L : Math.max(0L, stats.uptimeMillis()))));
		server(specs, "uptime_ms", "Uptime in milliseconds", (status, stats) -> Long.toString(stats == null ? 0L : Math.max(0L, stats.uptimeMillis())));
		server(specs, "system_cpu", "System CPU percent, one decimal", (status, stats) -> percent(stats == null ? 0D : stats.systemCpuUsagePercent()));
		server(specs, "process_cpu", "Process CPU percent, one decimal", (status, stats) -> percent(stats == null ? 0D : stats.processCpuUsagePercent()));
		server(specs, "ram_used_mb", "Heap in use, MB", (status, stats) -> Long.toString(stats == null ? 0L : Math.max(0L, stats.ramUsedBytes()) / (1024L * 1024L)));
		server(specs, "ram_max_mb", "Heap ceiling, MB", (status, stats) -> Long.toString(stats == null ? 0L : Math.max(0L, stats.ramMaxBytes()) / (1024L * 1024L)));
		server(specs, "ram_percent", "Heap in use, percent", (status, stats) -> stats == null || stats.ramMaxBytes() <= 0L ? "0.0" : percent(stats.ramUsedBytes() * 100D / stats.ramMaxBytes()));
		server(specs, "latency", "Heartbeat round trip in ms", (status, stats) -> Long.toString(stats == null ? 0L : Math.max(0L, stats.heartbeatLatencyMillis())));
		server(specs, "last_heartbeat", "Seconds since the last heartbeat", (status, stats) -> status == null ? "-" : Long.toString(Math.max(0L, (System.currentTimeMillis() - status.lastHeartbeatEpochMillis()) / 1000L)));

		display(specs, "display", "Display name from the server list", displayResolver::serverDisplay);
		display(specs, "color", "Accent colour from the server list", name -> {
			String color = displayResolver.serverColor(name);
			return color == null || color.isBlank() ? ServerSample.DEFAULT_ACCENT_COLOR : color;
		});

		player(specs, "player_name", "The viewer's account name", request -> request.viewer().name());
		player(specs, "player_server", "The backend the viewer is on", request -> request.viewer().worldName());
		player(specs, "player_group_name", "The viewer's primary group", request -> request.viewer().groupName());
		player(specs, "player_group_display", "The viewer's primary group display name", request -> request.viewer().groupDisplayName());
		player(specs, "player_prefix", "The viewer's prefix", request -> request.viewer().prefix());
		player(specs, "player_suffix", "The viewer's suffix", request -> request.viewer().suffix());
		player(specs, "player_display", "The viewer rendered with the configured display format",
			request -> request.player() == null
				? displayFormat.format(request.viewer().id(), request.viewer().name())
				: displayFormat.format(request.player()));

		specs.add(PlaceholderSpec.of(NAMESPACE, "player_status[_<dot:rest>]", PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER,
			"A glyph coloured by the accent of the viewer's current server; the glyph is the argument",
			request -> {
				String dot = request.param("dot", "▋");
				String backend = request.viewer() == null ? "" : request.viewer().worldName();
				String color = backend.isBlank() ? null : displayResolver.serverColor(backend);
				return "<c:" + (color == null || color.isBlank() ? ServerSample.DEFAULT_ACCENT_COLOR : color) + ">" + dot + "<reset>";
			}));

		return specs;
	}

	private interface StatsResolver {
		String resolve(BackendServerStatus status, BackendHeartbeatStats stats);
	}

	private void network(List<PlaceholderSpec<Player>> specs, String key, String description, Function<PlaceholderRequest<Player>, String> resolver) {
		specs.add(PlaceholderSpec.of(NAMESPACE, key, PlaceholderScope.SERVER, PlaceholderRefresh.SERVER, description, resolver::apply));
	}

	private void server(List<PlaceholderSpec<Player>> specs, String field, String description, StatsResolver resolver) {
		specs.add(PlaceholderSpec.of(NAMESPACE, "server_" + field + "[_<backend:word>]", PlaceholderScope.PLAYER, PlaceholderRefresh.SERVER,
			description + "; the backend is the argument, else the viewer's current server",
			request -> {
				String backend = backendOf(request);

				if (backend.isBlank()) {
					return null;
				}

				BackendServerStatus status = statusView.status(backend).orElse(null);
				return resolver.resolve(status, status == null ? null : status.stats());
			}));
	}

	private void display(List<PlaceholderSpec<Player>> specs, String field, String description, Function<String, String> resolver) {
		specs.add(PlaceholderSpec.of(NAMESPACE, "server_" + field + "[_<backend:word>]", PlaceholderScope.PLAYER, PlaceholderRefresh.SERVER,
			description + "; the backend is the argument, else the viewer's current server",
			request -> {
				String backend = backendOf(request);

				if (backend.isBlank()) {
					return null;
				}

				String value = resolver.apply(backend);
				return value == null || value.isBlank() ? backend : value;
			}));
	}

	private void player(List<PlaceholderSpec<Player>> specs, String key, String description, Function<PlaceholderRequest<Player>, String> resolver) {
		specs.add(PlaceholderSpec.of(NAMESPACE, key, PlaceholderScope.PLAYER, PlaceholderRefresh.PLAYER, description,
			request -> request.viewer() == null ? null : resolver.apply(request)));
	}

	/** The backend a request is about: the argument, else the viewer's current server. */
	private static String backendOf(PlaceholderRequest<Player> request) {
		String explicit = request.param("backend");

		if (explicit != null && !explicit.isBlank()) {
			return explicit.trim().toLowerCase(Locale.ROOT);
		}

		return request.viewer() == null ? "" : request.viewer().worldName().toLowerCase(Locale.ROOT);
	}

	private int onlineServers() {
		int count = 0;

		for (BackendServerStatus status : statusView.snapshot().values()) {
			if (status != null && status.online()) {
				count++;
			}
		}

		return count;
	}

	private int totalPlayers() {
		int total = 0;

		for (BackendServerStatus status : statusView.snapshot().values()) {
			if (status != null && status.stats() != null) {
				total += Math.max(0, status.stats().onlinePlayers());
			}
		}

		return total;
	}

	private static String percent(double value) {
		double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0D : Math.max(0D, Math.min(100D, value));
		return String.format(Locale.US, "%.1f", safe);
	}
}
