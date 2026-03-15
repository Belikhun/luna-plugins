package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.velocity.messaging.VelocityPluginMessagingBus;
import dev.belikhun.luna.core.velocity.serverselector.ServerSelectorOpenPayloadWriter;
import dev.belikhun.luna.core.velocity.serverselector.ServerSelectorStatus;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import dev.belikhun.luna.core.velocity.heartbeat.VelocityBackendStatusRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VelocityServerConnectCommand implements SimpleCommand {
	private final ProxyServer proxyServer;
	private final VelocityBackendStatusRegistry statusRegistry;
	private final VelocityPluginMessagingBus messagingBus;
	private final VelocityServerSelectorConfig config;
	private final boolean openMenuOnEmpty;

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
				source.sendRichMessage("<yellow>ℹ Cú pháp: <white>/connect <server></white></yellow>");
				return;
			}
			openSelector(player);
			return;
		}
		connectByName(player, args[0]);
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length > 1) {
			return List.of();
		}

		String input = args.length == 0 ? "" : args[0];
		List<String> names = new ArrayList<>(config.servers().keySet());
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
			ServerSelectorOpenPayloadWriter.write(writer, config);
		});
	}

	public void connectByName(Player player, String backendNameInput) {
		String backendName = backendNameInput == null ? "" : backendNameInput.trim().toLowerCase(Locale.ROOT);
		VelocityServerSelectorConfig.ServerDefinition definition = config.server(backendName);
		if (definition == null) {
			player.sendRichMessage(render(config.notFoundMessage(), placeholderValues(player, backendName, null, ServerSelectorStatus.OFFLINE)));
			return;
		}

		ServerSelectorStatus status = resolveStatus(player, definition);
		if (status != ServerSelectorStatus.ONLINE) {
			player.sendRichMessage(messageForStatus(status, placeholderValues(player, definition.backendName(), definition, status)));
			return;
		}

		RegisteredServer target = proxyServer.getServer(definition.backendName()).orElse(null);
		if (target == null) {
			player.sendRichMessage(render(config.offlineMessage(), placeholderValues(player, definition.backendName(), definition, ServerSelectorStatus.OFFLINE)));
			return;
		}

		String connectTemplate = definition.connectMessage() == null || definition.connectMessage().isBlank()
			? config.connectingMessage()
			: definition.connectMessage();
		player.sendRichMessage(render(connectTemplate, placeholderValues(player, definition.backendName(), definition, ServerSelectorStatus.ONLINE)));
		player.createConnectionRequest(target).fireAndForget();
	}

	private ServerSelectorStatus resolveStatus(Player player, VelocityServerSelectorConfig.ServerDefinition definition) {
		if (definition.permission() != null && !definition.permission().isBlank() && !player.hasPermission(definition.permission())) {
			return ServerSelectorStatus.NOP;
		}

		BackendServerStatus status = statusRegistry.status(definition.backendName()).orElse(null);
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
		String version = backendStatus != null && backendStatus.stats() != null ? backendStatus.stats().version() : "unknown";

		values.put("player_name", player.getUsername());
		values.put("server_name", backendName);
		values.put("server_display", display);
		values.put("server_accent_color", accent);
		values.put("server_status", status.name());
		values.put("server_status_color", config.color(status));
		values.put("online", String.valueOf(online));
		values.put("max", String.valueOf(max));
		values.put("tps", String.format(Locale.US, "%.2f", tps));
		values.put("uptime", String.valueOf(Math.max(0L, uptime / 1000L)));
		values.put("version", version);
		return values;
	}

	private String render(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			output = output.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
		}
		return output;
	}
}
