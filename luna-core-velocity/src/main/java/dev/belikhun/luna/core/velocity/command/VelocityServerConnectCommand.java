package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.core.velocity.serverselector.ServerSelectorStatus;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;

import java.util.ArrayList;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityServerConnectCommand implements SimpleCommand {
	public static final String ACTION_LOBBY = "__lobby__";
	public static final String ACTION_PREVIOUS = "__previous__";
	private static final Pattern MC_VERSION_PATTERN = Pattern.compile("\\(MC:\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEMVER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3})");

	private final ProxyServer proxyServer;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityPluginMessagingBus messagingBus;
	private final VelocityServerSelectorConfig config;
	private final boolean openMenuOnEmpty;
	private final Map<UUID, String> previousServerByPlayer;

	public VelocityServerConnectCommand(
		ProxyServer proxyServer,
		VelocityBackendStatusRegistry statusRegistry,
		VelocityPluginMessagingBus messagingBus,
		VelocityServerSelectorConfig config,
		boolean openMenuOnEmpty
	) {
		this.proxyServer = proxyServer;
		this.statusRegistry = statusRegistry;
		this.messagingBus = messagingBus;
		this.config = config;
		this.openMenuOnEmpty = openMenuOnEmpty;
		this.previousServerByPlayer = new ConcurrentHashMap<>();
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!(source instanceof Player player)) {
			source.sendRichMessage(config.playerOnlyMessage());
			return;
		}

		String[] args = invocation.arguments();
		if (args.length == 0) {
			if (!openMenuOnEmpty) {
				source.sendRichMessage(CommandStrings.usage("/connect", CommandStrings.required("server", "text")));
				return;
			}
			openSelector(player);
			return;
		}
		connectByName(player, args[0]);
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (!(invocation.source() instanceof Player player)) {
			return List.of();
		}

		String[] args = invocation.arguments();
		if (args.length > 1) {
			return List.of();
		}

		String input = args.length == 0 ? "" : args[0];
		java.util.Set<String> candidateNames = new java.util.LinkedHashSet<>(config.knownServerNames());
		for (RegisteredServer server : proxyServer.getAllServers()) {
			candidateNames.add(normalize(server.getServerInfo().getName()));
		}

		List<String> names = new ArrayList<>();
		for (String name : candidateNames) {
			if (name == null || name.isBlank()) {
				continue;
			}

			VelocityServerSelectorConfig.ServerDefinition definition = config.server(name);
			ServerSelectorStatus status = resolveStatus(player, name, definition == null ? "" : definition.permission());
			if (status == ServerSelectorStatus.ONLINE) {
				names.add(name);
			}
		}

		names.sort(String.CASE_INSENSITIVE_ORDER);
		String lower = input.toLowerCase(Locale.ROOT);
		return names.stream().filter(name -> name.startsWith(lower)).toList();
	}

	public void openSelector(Player player) {
		player.sendRichMessage(config.openingMessage());
		ServerConnection connection = player.getCurrentServer().orElse(null);
		if (connection == null) {
			player.sendRichMessage("<red>❌ Không thể mở menu khi chưa ở backend.</red>");
			return;
		}

		messagingBus.send(connection, CoreServerSelectorMessageChannels.OPEN_MENU, writer -> {
		});
	}

	public void connectByName(Player player, String backendNameInput) {
		String backendName = normalize(backendNameInput);
		if (backendName.isBlank()) {
			player.sendRichMessage(render(config.notFoundMessage(), placeholderValues(player, backendName, null, ServerSelectorStatus.OFFLINE)));
			return;
		}

		if (ACTION_LOBBY.equals(backendName)) {
			backendName = "lobby";
		} else if (ACTION_PREVIOUS.equals(backendName)) {
			String previous = previousServerByPlayer.get(player.getUniqueId());
			if (previous == null || previous.isBlank()) {
				player.sendRichMessage("<red>❌ Không có máy chủ trước đó để quay lại.</red>");
				return;
			}
			backendName = previous;
		}

		VelocityServerSelectorConfig.ServerDefinition definition = config.server(backendName);
		RegisteredServer target = proxyServer.getServer(backendName).orElse(null);
		if (definition == null && target == null) {
			player.sendRichMessage(render(config.notFoundMessage(), placeholderValues(player, backendName, null, ServerSelectorStatus.OFFLINE)));
			return;
		}

		String resolvedBackend = definition != null ? definition.backendName() : backendName;
		ServerSelectorStatus status = resolveStatus(player, resolvedBackend, definition == null ? "" : definition.permission());
		if (status != ServerSelectorStatus.ONLINE) {
			player.sendRichMessage(messageForStatus(status, placeholderValues(player, resolvedBackend, definition, status)));
			return;
		}

		if (target == null) {
			target = proxyServer.getServer(resolvedBackend).orElse(null);
		}
		if (target == null) {
			player.sendRichMessage(render(config.offlineMessage(), placeholderValues(player, resolvedBackend, definition, ServerSelectorStatus.OFFLINE)));
			return;
		}

		String connectTemplate = definition == null || definition.connectMessage() == null || definition.connectMessage().isBlank()
			? config.connectingMessage()
			: definition.connectMessage();
		player.sendRichMessage(render(connectTemplate, placeholderValues(player, resolvedBackend, definition, ServerSelectorStatus.ONLINE)));
		rememberPrevious(player, resolvedBackend);
		player.createConnectionRequest(target).fireAndForget();
	}

	private ServerSelectorStatus resolveStatus(Player player, String backendName, String permission) {
		if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
			return ServerSelectorStatus.NOP;
		}

		BackendServerStatus status = statusRegistry.status(backendName).orElse(null);
		if (status == null || !status.online()) {
			return ServerSelectorStatus.OFFLINE;
		}

		if (status.stats() != null && status.stats().whitelistEnabled()) {
			return ServerSelectorStatus.MAINT;
		}

		return ServerSelectorStatus.ONLINE;
	}

	private String messageForStatus(ServerSelectorStatus status, Map<String, String> values) {
		if (status == ServerSelectorStatus.NOP) {
			return render(config.noPermissionMessage(), values);
		}
		if (status == ServerSelectorStatus.MAINT) {
			return render(config.maintMessage(), values);
		}
		return render(config.offlineMessage(), values);
	}

	private Map<String, String> placeholderValues(
		Player player,
		String backendName,
		VelocityServerSelectorConfig.ServerDefinition definition,
		ServerSelectorStatus status
	) {
		Map<String, String> values = new LinkedHashMap<>();
		BackendServerStatus backendStatus = statusRegistry.status(backendName).orElse(null);
		String display = definition != null ? definition.displayName() : backendName;
		String accent = definition != null ? definition.accentColor() : "";
		String serverInfoName = backendName;
		if (definition == null) {
			VelocityServerSelectorConfig.ServerInfo info = config.serverInfo(backendName);
			if (info != null) {
				if (info.displayName() != null && !info.displayName().isBlank()) {
					display = info.displayName();
				}
				if (accent.isBlank() && info.accentColor() != null && !info.accentColor().isBlank()) {
					accent = info.accentColor();
				}
				if (info.serverName() != null && !info.serverName().isBlank()) {
					serverInfoName = info.serverName();
				}
			}
		} else {
			VelocityServerSelectorConfig.ServerInfo info = config.serverInfo(backendName);
			if (info != null && info.serverName() != null && !info.serverName().isBlank()) {
				serverInfoName = info.serverName();
			}
		}
		if (serverInfoName.isBlank()) {
			serverInfoName = backendHost(backendName);
		}
		if (backendStatus != null) {
			display = backendStatus.serverDisplay();
			if (accent.isBlank()) {
				accent = backendStatus.serverAccentColor();
			}
		}

		int online = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().onlinePlayers() : 0;
		int max = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().maxPlayers() : 0;
		double tps = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().tps() : 0D;
		long uptime = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().uptimeMillis() : 0L;
		double cpu = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().systemCpuUsagePercent() : 0D;
		long ramUsedBytes = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().ramUsedBytes() : 0L;
		long ramMaxBytes = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().ramMaxBytes() : 0L;
		long latencyMs = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().heartbeatLatencyMillis() : 0L;
		String software = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().software() : "unknown";
		String versionFull = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().version() : "unknown";
		String versionShort = shortVersion(versionFull);

		values.put("player_name", player.getUsername());
		values.put("server_name", backendName);
		values.put("luna_host_name", serverInfoName);
		values.put("luna_server_name", serverInfoName);
		values.put("server_display", display);
		values.put("server_accent_color", accent);
		values.put("server_status", status.name());
		values.put("server_status_color", config.color(status));
		values.put("server_status_icon", config.icon(status));
		values.put("online", String.valueOf(online));
		values.put("max", String.valueOf(max));
		values.put("tps", String.format(Locale.US, "%.2f", tps));
		long safeUptime = Math.max(0L, uptime);
		values.put("uptime", Formatters.compactDuration(Duration.ofMillis(safeUptime)));
		values.put("uptime_long", Formatters.duration(Duration.ofMillis(safeUptime)));
		values.put("cpu_usage", String.format(Locale.US, "%.1f", Math.max(0D, cpu)));
		values.put("ram_used_mb", String.valueOf(Math.max(0L, ramUsedBytes / 1024L / 1024L)));
		values.put("ram_max_mb", String.valueOf(Math.max(0L, ramMaxBytes / 1024L / 1024L)));
		values.put("ram_percent", String.format(Locale.US, "%.1f", ramMaxBytes <= 0L ? 0D : Math.min(100D, (ramUsedBytes * 100D) / ramMaxBytes)));
		values.put("latency_ms", String.valueOf(Math.max(0L, latencyMs)));
		values.put("software", software == null || software.isBlank() ? "unknown" : software);
		values.put("server_software", software == null || software.isBlank() ? "unknown" : software);
		values.put("version", versionShort);
		values.put("server_version", versionShort);
		values.put("server_version_full", versionFull);
		return values;
	}

	private String render(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			output = output.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
		}
		return output;
	}

	private void rememberPrevious(Player player, String targetBackend) {
		if (player == null || targetBackend == null || targetBackend.isBlank()) {
			return;
		}

		String current = player.getCurrentServer()
			.map(connection -> normalize(connection.getServerInfo().getName()))
			.orElse("");
		String target = normalize(targetBackend);
		if (!current.isBlank() && !current.equals(target)) {
			previousServerByPlayer.put(player.getUniqueId(), current);
		}
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private String backendHost(String backendName) {
		if (backendName == null || backendName.isBlank()) {
			return "";
		}

		RegisteredServer server = proxyServer.getServer(backendName).orElse(null);
		if (server == null || server.getServerInfo() == null || server.getServerInfo().getAddress() == null) {
			return backendName;
		}

		String host = server.getServerInfo().getAddress().getHostString();
		return host == null || host.isBlank() ? backendName : host.trim();
	}

	private String shortVersion(String full) {
		if (full == null || full.isBlank()) {
			return "unknown";
		}

		Matcher mcMatcher = MC_VERSION_PATTERN.matcher(full);
		if (mcMatcher.find()) {
			return mcMatcher.group(1).trim();
		}

		Matcher semverMatcher = SEMVER_PATTERN.matcher(full);
		if (semverMatcher.find()) {
			return semverMatcher.group(1).trim();
		}

		return full.trim();
	}
}
