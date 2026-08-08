package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.belikhun.luna.core.api.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaProgressBarPresets;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LunaCoreVelocityAdminCommand implements SimpleCommand {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final Pattern MC_VERSION_PATTERN = Pattern.compile("\\(MC:\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEMVER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3})");
	private final BackendStatusView statusView;
	private final ReloadAction reloadAction;

	@FunctionalInterface
	public interface ReloadAction {
		void reload() throws Exception;
	}

	public LunaCoreVelocityAdminCommand(BackendStatusView statusView, ReloadAction reloadAction) {
		this.statusView = statusView;
		this.reloadAction = reloadAction;
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
			sendUsage(source);
			return;
		}

		if (args[0].equalsIgnoreCase("reload")) {
			source.sendRichMessage("<yellow>⌛ Đang reload LunaCore Velocity...</yellow>");
			try {
				reloadAction.reload();
				source.sendRichMessage("<green>✔ Reload LunaCore Velocity thành công.</green>");
			} catch (Exception exception) {
				source.sendRichMessage("<red>❌ Reload thất bại: <white>" + MM.escapeTags(String.valueOf(exception.getMessage())) + "</white></red>");
			}
			return;
		}

		if (!args[0].equalsIgnoreCase("status")) {
			sendUsage(source);
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
			return List.of("status", "reload");
		}

		if (args.length == 1) {
			List<String> suggestions = new ArrayList<>();
			suggestions.addAll(startsWith("status", args[0]));
			suggestions.addAll(startsWith("reload", args[0]));
			return suggestions;
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
			List<String> names = new ArrayList<>(statusView.snapshot().keySet());
			names.sort(String.CASE_INSENSITIVE_ORDER);
			return names.stream().filter(name -> startsWithIgnoreCase(name, args[1])).toList();
		}

		return List.of();
	}

	private void sendUsage(CommandSource source) {
		source.sendRichMessage(CommandStrings.usage("/lunacoreproxy", CommandStrings.literal("status"), CommandStrings.optional("server", "text")));
		source.sendRichMessage(CommandStrings.usage("/lunacoreproxy", CommandStrings.literal("reload")));
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
			String processCpu = status.stats() == null ? "0.0" : String.format(Locale.US, "%.1f", Math.max(0D, status.stats().processCpuUsagePercent()));
			String systemCpu = status.stats() == null ? "0.0" : String.format(Locale.US, "%.1f", Math.max(0D, status.stats().systemCpuUsagePercent()));
			String latency = status.stats() == null ? "0" : String.valueOf(Math.max(0L, status.stats().heartbeatLatencyMillis()));
			source.sendRichMessage("<gray>-</gray> <aqua>" + MM.escapeTags(status.serverName()) + "</aqua> " + indicator
				+ " <gray>| players:</gray> <white>" + players + "</white>"
				+ " <gray>| tps:</gray> <white>" + formatTps(status.stats()) + "</white>"
				+ " <gray>| cpu-p:</gray> <white>" + processCpu + "%</white>"
				+ " <gray>| cpu-s:</gray> <white>" + systemCpu + "%</white>"
				+ " <gray>| latency:</gray> <white>" + latency + "ms</white>"
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

		source.sendRichMessage("<gray>Software:</gray> <white>" + MM.escapeTags(stats.software()) + "</white> <gray>| Version:</gray> <white>" + MM.escapeTags(shortVersion(stats.version())) + "</white>");
		source.sendRichMessage("<gray>Version đầy đủ:</gray> <white>" + MM.escapeTags(stats.version()) + "</white>");
		source.sendRichMessage("<gray>Players:</gray> <white>" + stats.onlinePlayers() + "/" + stats.maxPlayers() + "</white> <gray>| TPS:</gray> <white>" + formatTps(stats) + "</white>");
		source.sendRichMessage("<gray>TPS Bar:</gray> " + LunaProgressBarPresets.tps("TPS", stats.tps()).render());
		source.sendRichMessage("<gray>Uptime:</gray> <white>" + Formatters.duration(Duration.ofMillis(Math.max(0L, stats.uptimeMillis()))) + "</white> <gray>| Port:</gray> <white>" + stats.serverPort() + "</white>");
		source.sendRichMessage("<gray>CPU Process:</gray> <white>" + String.format(Locale.US, "%.1f", Math.max(0D, stats.processCpuUsagePercent())) + "%</white> <gray>| CPU System:</gray> <white>" + String.format(Locale.US, "%.1f", Math.max(0D, stats.systemCpuUsagePercent())) + "%</white> <gray>| Latency:</gray> <white>" + Math.max(0L, stats.heartbeatLatencyMillis()) + "ms</white>");
		source.sendRichMessage("<gray>CPU Process Bar:</gray> " + LunaProgressBarPresets.cpu("CPU-P", stats.processCpuUsagePercent()).render());
		source.sendRichMessage("<gray>CPU System Bar:</gray> " + LunaProgressBarPresets.cpu("CPU-S", stats.systemCpuUsagePercent()).render());
		source.sendRichMessage("<gray>Latency Bar:</gray> " + LunaProgressBarPresets.latency("LAT", stats.heartbeatLatencyMillis()).render());
		source.sendRichMessage("<gray>Whitelist:</gray> <white>" + (stats.whitelistEnabled() ? "ON" : "OFF") + "</white>");
		source.sendRichMessage("<gray>RAM:</gray> <white>" + formatMb(stats.ramUsedBytes()) + "MB used, " + formatMb(stats.ramFreeBytes()) + "MB free, " + formatMb(stats.ramMaxBytes()) + "MB max</white>");
		source.sendRichMessage("<gray>RAM Bar:</gray> " + LunaProgressBarPresets.ram("RAM", stats.ramUsedBytes(), stats.ramMaxBytes()).render());
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
