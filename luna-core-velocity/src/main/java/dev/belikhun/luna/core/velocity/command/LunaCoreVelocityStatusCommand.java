package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.string.Formatters;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LunaCoreVelocityStatusCommand implements SimpleCommand {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private final BackendStatusView statusView;

	public LunaCoreVelocityStatusCommand(BackendStatusView statusView) {
		this.statusView = statusView;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!source.hasPermission("lunacore.admin")) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		String[] args = invocation.arguments();
		if (args.length == 0) {
			source.sendRichMessage("<yellow>ℹ Cú pháp: <white>/lunacoreproxy status [server]</white></yellow>");
			return;
		}

		if (!args[0].equalsIgnoreCase("status")) {
			source.sendRichMessage("<yellow>ℹ Cú pháp: <white>/lunacoreproxy status [server]</white></yellow>");
			return;
		}

		if (args.length == 1) {
			showAll(source);
			return;
		}

		showOne(source, args[1]);
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0) {
			return List.of("status");
		}

		if (args.length == 1) {
			return startsWith("status", args[0]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
			List<String> names = new ArrayList<>(statusView.snapshot().keySet());
			names.sort(String.CASE_INSENSITIVE_ORDER);
			return names.stream().filter(name -> startsWithIgnoreCase(name, args[1])).toList();
		}

		return List.of();
	}

	private void showAll(CommandSource source) {
		Map<String, BackendServerStatus> snapshot = statusView.snapshot();
		if (snapshot.isEmpty()) {
			source.sendRichMessage("<yellow>ℹ Chưa có dữ liệu heartbeat từ backend nào.</yellow>");
			return;
		}

		List<BackendServerStatus> values = new ArrayList<>(snapshot.values());
		values.sort(Comparator.comparing(status -> status.serverName().toLowerCase(Locale.ROOT)));

		source.sendRichMessage("<gold><b>LunaCore Backend Status</b></gold> <gray>(" + values.size() + " server)</gray>");
		for (BackendServerStatus status : values) {
			String indicator = status.online() ? "<green>● ONLINE</green>" : "<red>● OFFLINE</red>";
			String players = status.stats() == null ? "0/0" : status.stats().onlinePlayers() + "/" + status.stats().maxPlayers();
			source.sendRichMessage("<gray>-</gray> <aqua>" + MM.escapeTags(status.serverName()) + "</aqua> " + indicator
				+ " <gray>| players:</gray> <white>" + players + "</white>"
				+ " <gray>| tps:</gray> <white>" + formatTps(status.stats()) + "</white>"
				+ " <gray>| last:</gray> <white>" + formatAgo(status.lastHeartbeatEpochMillis()) + "</white>");
		}
	}

	private void showOne(CommandSource source, String serverName) {
		BackendServerStatus status = statusView.status(serverName).orElse(null);
		if (status == null) {
			source.sendRichMessage("<red>❌ Không tìm thấy backend: <white>" + MM.escapeTags(serverName) + "</white></red>");
			return;
		}

		BackendHeartbeatStats stats = status.stats();
		source.sendRichMessage("<gold><b>Backend:</b></gold> <aqua>" + MM.escapeTags(status.serverName()) + "</aqua>");
		source.sendRichMessage("<gray>Trạng thái:</gray> " + (status.online() ? "<green>ONLINE</green>" : "<red>OFFLINE</red>"));
		source.sendRichMessage("<gray>Heartbeat cuối:</gray> <white>" + formatAgo(status.lastHeartbeatEpochMillis()) + "</white>");
		if (stats == null) {
			source.sendRichMessage("<yellow>ℹ Không có stats chi tiết.</yellow>");
			return;
		}

		source.sendRichMessage("<gray>Software:</gray> <white>" + MM.escapeTags(stats.software()) + "</white> <gray>| Version:</gray> <white>" + MM.escapeTags(stats.version()) + "</white>");
		source.sendRichMessage("<gray>Players:</gray> <white>" + stats.onlinePlayers() + "/" + stats.maxPlayers() + "</white> <gray>| TPS:</gray> <white>" + formatTps(stats) + "</white>");
		source.sendRichMessage("<gray>Uptime:</gray> <white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, stats.uptimeMillis()))) + "</white> <gray>| Port:</gray> <white>" + stats.serverPort() + "</white>");
		source.sendRichMessage("<gray>Whitelist:</gray> <white>" + (stats.whitelistEnabled() ? "ON" : "OFF") + "</white>");
		source.sendRichMessage("<gray>RAM:</gray> <white>" + formatMb(stats.ramUsedBytes()) + "MB used, " + formatMb(stats.ramFreeBytes()) + "MB free, " + formatMb(stats.ramMaxBytes()) + "MB max</white>");
		source.sendRichMessage("<gray>MOTD:</gray> <white>" + MM.escapeTags(stats.motd()) + "</white>");
	}

	private List<String> startsWith(String value, String input) {
		if (startsWithIgnoreCase(value, input)) {
			return List.of(value);
		}
		return List.of();
	}

	private boolean startsWithIgnoreCase(String value, String input) {
		if (input == null || input.isBlank()) {
			return true;
		}
		return value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT));
	}

	private String formatTps(BackendHeartbeatStats stats) {
		if (stats == null) {
			return "0.00";
		}
		return String.format(Locale.US, "%.2f", stats.tps());
	}

	private String formatAgo(long atEpochMillis) {
		long now = System.currentTimeMillis();
		long delta = Math.max(0L, now - atEpochMillis);
		return Formatters.duration(Duration.ofMillis(delta)) + " trước";
	}

	private long formatMb(long bytes) {
		return Math.max(0L, bytes) / (1024L * 1024L);
	}
}
