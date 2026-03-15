package dev.belikhun.luna.core.velocity.placeholder;

import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.velocity.BuildConstants;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;

import java.util.Locale;
import java.util.Map;

public final class VelocityLunaMiniPlaceholders {
	private static final String DEFAULT_COLOR = "#F1FF68";

	private final LunaLogger logger;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityServerSelectorConfig selectorConfig;
	private final ServerDisplayResolver serverDisplayResolver;
	private Expansion expansion;

	public VelocityLunaMiniPlaceholders(
		LunaLogger logger,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityServerSelectorConfig selectorConfig,
		ServerDisplayResolver serverDisplayResolver
	) {
		this.logger = logger.scope("MiniPlaceholders");
		this.statusRegistry = statusRegistry;
		this.selectorConfig = selectorConfig;
		this.serverDisplayResolver = serverDisplayResolver;
	}

	public void register() {
		if (expansion != null && expansion.registered()) {
			return;
		}

		Expansion.Builder builder = Expansion.builder("luna")
			.author("Belikhun")
			.version(BuildConstants.VERSION)
			.globalPlaceholder("online_servers", (queue, context) -> textTag(Integer.toString(onlineServers())))
			.globalPlaceholder("registered_servers", (queue, context) -> textTag(Integer.toString(registeredServers())))
			.globalPlaceholder("total_servers", (queue, context) -> textTag(Integer.toString(registeredServers())))
			.globalPlaceholder("total_players", (queue, context) -> textTag(Integer.toString(totalPlayers())));

		for (VelocityServerSelectorConfig.ServerDefinition definition : selectorConfig.servers().values()) {
			String key = normalize(definition.backendName());
			if (key.isBlank()) {
				continue;
			}

			builder
				.globalPlaceholder("server_status_" + key, (queue, context) -> textTag(serverStatus(key)))
				.globalPlaceholder("server_online_" + key, (queue, context) -> textTag(Integer.toString(serverOnline(key))))
				.globalPlaceholder("server_max_" + key, (queue, context) -> textTag(Integer.toString(serverMax(key))))
				.globalPlaceholder("server_tps_" + key, (queue, context) -> textTag(serverTps(key)))
				.globalPlaceholder("server_version_" + key, (queue, context) -> textTag(serverVersion(key)))
				.globalPlaceholder("server_display_" + key, (queue, context) -> textTag(serverDisplay(key)))
				.globalPlaceholder("server_color_" + key, (queue, context) -> textTag(serverColor(key)))
				.globalPlaceholder("server_whitelist_" + key, (queue, context) -> textTag(Boolean.toString(serverWhitelist(key))));
		}

		expansion = builder.build();
		expansion.register();
		logger.success("Đã đăng ký MiniPlaceholders namespace <luna> cho Velocity.");
	}

	public void unregister() {
		if (expansion == null) {
			return;
		}

		if (expansion.registered()) {
			expansion.unregister();
		}
		expansion = null;
	}

	private int registeredServers() {
		return selectorConfig.servers().size();
	}

	private int onlineServers() {
		int count = 0;
		Map<String, BackendServerStatus> snapshot = statusRegistry.snapshot();
		for (BackendServerStatus status : snapshot.values()) {
			if (status != null && status.online()) {
				count++;
			}
		}
		return count;
	}

	private int totalPlayers() {
		int total = 0;
		Map<String, BackendServerStatus> snapshot = statusRegistry.snapshot();
		for (BackendServerStatus status : snapshot.values()) {
			if (status == null || status.stats() == null) {
				continue;
			}
			total += Math.max(0, status.stats().onlinePlayers());
		}
		return total;
	}

	private String serverStatus(String serverName) {
		BackendServerStatus status = status(serverName);
		if (status == null || !status.online()) {
			return "OFFLINE";
		}

		BackendHeartbeatStats stats = status.stats();
		if (stats != null && stats.whitelistEnabled()) {
			return "MAINT";
		}

		return "ONLINE";
	}

	private int serverOnline(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.onlinePlayers());
	}

	private int serverMax(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null ? 0 : Math.max(0, stats.maxPlayers());
	}

	private String serverTps(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		double value = stats == null ? 0D : stats.tps();
		return String.format(Locale.US, "%.2f", value);
	}

	private String serverVersion(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats == null || stats.version() == null || stats.version().isBlank() ? "unknown" : stats.version();
	}

	private String serverDisplay(String serverName) {
		String display = serverDisplayResolver.serverDisplay(serverName);
		if (display == null || display.isBlank()) {
			return serverName;
		}
		return display;
	}

	private String serverColor(String serverName) {
		String color = serverDisplayResolver.serverColor(serverName);
		if (color == null || color.isBlank()) {
			return DEFAULT_COLOR;
		}
		return color;
	}

	private boolean serverWhitelist(String serverName) {
		BackendHeartbeatStats stats = stats(serverName);
		return stats != null && stats.whitelistEnabled();
	}

	private BackendHeartbeatStats stats(String serverName) {
		BackendServerStatus status = status(serverName);
		return status == null ? null : status.stats();
	}

	private BackendServerStatus status(String serverName) {
		return statusRegistry.status(serverName).orElse(null);
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private Tag textTag(String value) {
		return Tag.inserting(Component.text(value == null ? "" : value));
	}
}
