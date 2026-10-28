package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.velocity.network.NetworkBanMessages;
import dev.belikhun.luna.core.velocity.network.NetworkModeration;
import dev.belikhun.luna.core.velocity.network.VelocityNetworkBanStore;
import dev.belikhun.luna.core.velocity.network.VelocityNetworkPlayerBanStore;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The proxy's network-wide moderation commands. One class, one instance per
 * command, each bound to a {@link Mode}:
 *
 * <pre>
 * /netkick &lt;player&gt; [reason]
 * /netban &lt;player&gt; [duration] [reason]
 * /netunban &lt;player&gt;
 * /netbanip &lt;ip|player&gt; [duration] [reason]
 * /netunbanip &lt;ip&gt;
 * /netbans [ip]
 * </pre>
 *
 * A duration is 30m, 12h, 7d, 2w, 1d12h or perm; anything else is the first
 * word of the reason, so a ban without one is permanent.
 */
public final class NetworkModerationCommand implements SimpleCommand {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final int LIST_LIMIT = 20;

	public enum Mode {
		KICK("netkick", "lunacore.moderation.kick", "<người chơi> [lý do]"),
		BAN("netban", "lunacore.moderation.ban", "<người chơi> [thời hạn] [lý do]"),
		UNBAN("netunban", "lunacore.moderation.ban", "<người chơi>"),
		BAN_IP("netbanip", "lunacore.moderation.banip", "<ip|người chơi> [thời hạn] [lý do]"),
		UNBAN_IP("netunbanip", "lunacore.moderation.banip", "<ip>"),
		LIST("netbans", "lunacore.moderation.ban", "[ip]");

		public final String label;
		public final String permission;
		public final String usage;

		Mode(String label, String permission, String usage) {
			this.label = label;
			this.permission = permission;
			this.usage = usage;
		}
	}

	private final Mode mode;
	private final ProxyServer proxyServer;
	private final NetworkModeration moderation;

	public NetworkModerationCommand(Mode mode, ProxyServer proxyServer, NetworkModeration moderation) {
		this.mode = mode;
		this.proxyServer = proxyServer;
		this.moderation = moderation;
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		CommandSource source = invocation.source();
		return source.hasPermission(mode.permission) || source.hasPermission("lunacore.admin");
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		String[] args = invocation.arguments();

		if (mode == Mode.LIST) {
			list(source, args);
			return;
		}

		if (args.length == 0) {
			source.sendRichMessage("<yellow>Cách dùng: <white>/" + mode.label + " " + MM.escapeTags(mode.usage) + "</white></yellow>");

			if (mode == Mode.BAN || mode == Mode.BAN_IP) {
				source.sendRichMessage("<gray>Thời hạn: 30m, 12h, 7d, 2w, 1d12h hoặc perm. Bỏ trống là vĩnh viễn.</gray>");
			}

			return;
		}

		String target = args[0];
		String actor = actorOf(source);
		String[] rest = Arrays.copyOfRange(args, 1, args.length);

		switch (mode) {
			case KICK -> report(source, moderation.kick(target, String.join(" ", rest), actor), result ->
				"<green>✔ Đã kick <white>" + esc(result.get("username")) + "</white> khỏi mạng.</green>");

			case BAN -> {
				Terms terms = terms(rest);
				report(source, moderation.ban(target, terms.reason, actor, terms.expiresAt), result ->
					"<green>✔ Đã cấm <white>" + esc(result.get("username")) + "</white> khỏi mạng"
						+ " <gray>(" + NetworkBanMessages.expiryText(terms.expiresAt) + ")</gray>"
						+ (asInt(result.get("kicked")) > 0 ? " và ngắt kết nối." : ".")
						+ "</green>");
			}

			case UNBAN -> report(source, moderation.unban(target, "", actor), result ->
				Boolean.TRUE.equals(result.get("removed"))
					? "<green>✔ Đã gỡ cấm <white>" + esc(result.get("username")) + "</white>.</green>"
					: "<yellow>⚠ <white>" + esc(target) + "</white> không bị cấm.</yellow>");

			case BAN_IP -> {
				Terms terms = terms(rest);
				report(source, moderation.banIp(target, terms.reason, actor, terms.expiresAt), result -> {
					String from = String.valueOf(result.getOrDefault("resolvedFrom", ""));
					Object kicked = result.get("kicked");
					int kickedCount = kicked instanceof List<?> names ? names.size() : 0;

					return "<green>✔ Đã cấm IP <white>" + esc(result.get("ip")) + "</white>"
						+ (from.isBlank() ? "" : " <gray>(của " + esc(from) + ")</gray>")
						+ " <gray>(" + NetworkBanMessages.expiryText(terms.expiresAt) + ")</gray>"
						+ (kickedCount > 0 ? ", ngắt kết nối " + kickedCount + " người chơi." : ".")
						+ "</green>";
				});
			}

			case UNBAN_IP -> report(source, moderation.unbanIp(target, "", actor), result ->
				Boolean.TRUE.equals(result.get("removed"))
					? "<green>✔ Đã gỡ cấm IP <white>" + esc(result.get("ip")) + "</white>.</green>"
					: "<yellow>⚠ IP <white>" + esc(target) + "</white> không bị cấm.</yellow>");

			default -> {
			}
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();

		if (args.length > 1) {
			if ((mode == Mode.BAN || mode == Mode.BAN_IP) && args.length == 2) {
				return filter(List.of("30m", "1h", "12h", "1d", "7d", "30d", "perm"), args[1]);
			}

			return List.of();
		}

		String prefix = args.length == 0 ? "" : args[0];
		List<String> options = new ArrayList<>();

		switch (mode) {
			case KICK, BAN, BAN_IP -> {
				for (Player player : proxyServer.getAllPlayers()) {
					options.add(player.getUsername());
				}
			}

			case UNBAN -> {
				for (VelocityNetworkPlayerBanStore.Entry entry : moderation.playerBans().list()) {
					options.add(entry.username);
				}
			}

			case UNBAN_IP -> {
				for (VelocityNetworkBanStore.Entry entry : moderation.ipBans().list()) {
					options.add(entry.ip);
				}
			}

			case LIST -> options.add("ip");
		}

		return filter(options, prefix);
	}

	private void list(CommandSource source, String[] args) {
		boolean ips = args.length > 0 && args[0].equalsIgnoreCase("ip");

		if (ips) {
			List<VelocityNetworkBanStore.Entry> bans = moderation.ipBans().list();
			source.sendRichMessage("<gold>⛔ Cấm IP toàn mạng: <white>" + bans.size() + "</white></gold>");

			for (VelocityNetworkBanStore.Entry entry : bans.subList(0, Math.min(LIST_LIMIT, bans.size()))) {
				source.sendRichMessage(line(entry.ip, entry.reason, entry.actor, entry.expiresAt, entry.hits));
			}

			more(source, bans.size());
			return;
		}

		List<VelocityNetworkPlayerBanStore.Entry> bans = moderation.playerBans().list();
		source.sendRichMessage("<gold>⛔ Cấm người chơi toàn mạng: <white>" + bans.size() + "</white></gold>");

		for (VelocityNetworkPlayerBanStore.Entry entry : bans.subList(0, Math.min(LIST_LIMIT, bans.size()))) {
			source.sendRichMessage(line(entry.username, entry.reason, entry.actor, entry.expiresAt, entry.hits));
		}

		more(source, bans.size());
		source.sendRichMessage("<gray>Dùng <white>/netbans ip</white> để xem danh sách cấm IP.</gray>");
	}

	private String line(String target, String reason, String actor, long expiresAt, long hits) {
		return "<gray> • </gray><white>" + esc(target) + "</white>"
			+ (reason.isBlank() ? "" : " <gray>—</gray> " + esc(reason))
			+ " <dark_gray>(" + NetworkBanMessages.expiryText(expiresAt)
			+ (actor.isBlank() ? "" : ", bởi " + esc(actor))
			+ (hits > 0 ? ", chặn " + hits + " lần" : "")
			+ ")</dark_gray>";
	}

	private void more(CommandSource source, int total) {
		if (total > LIST_LIMIT) {
			source.sendRichMessage("<gray>… và " + (total - LIST_LIMIT) + " mục khác (xem đủ trên Luna Console).</gray>");
		}
	}

	private interface Describer {
		String describe(Map<String, Object> result);
	}

	private void report(CommandSource source, NetworkModeration.Result result, Describer describer) {
		if (!result.ok()) {
			source.sendRichMessage("<red>❌ " + esc(translate(result.error())) + "</red>");
			return;
		}

		source.sendRichMessage(describer.describe(result.data()));
	}

	/** The service speaks the API's English; players here read Vietnamese. */
	private String translate(String error) {
		if (error.startsWith("player is not online")) {
			return "Người chơi không trực tuyến.";
		}

		if (error.startsWith("not a known player")) {
			return "Không tìm thấy người chơi, và đây cũng không phải tên hợp lệ.";
		}

		if (error.startsWith("a valid bare IP address, or a player")) {
			return "Cần một địa chỉ IP, hoặc một người chơi đã biết địa chỉ.";
		}

		if (error.startsWith("a valid bare IP")) {
			return "Cần một địa chỉ IP hợp lệ.";
		}

		if (error.contains("database is not available")) {
			return "Cơ sở dữ liệu cấm chưa sẵn sàng.";
		}

		return error;
	}

	private record Terms(long expiresAt, String reason) {}

	private Terms terms(String[] rest) {
		if (rest.length > 0 && NetworkBanMessages.isDuration(rest[0])) {
			long length = NetworkBanMessages.parseDuration(rest[0]);
			long expiresAt = length > 0 ? System.currentTimeMillis() + length : 0L;
			return new Terms(expiresAt, String.join(" ", Arrays.copyOfRange(rest, 1, rest.length)));
		}

		return new Terms(0L, String.join(" ", rest));
	}

	private static String actorOf(CommandSource source) {
		if (source instanceof Player player) {
			return player.getUsername();
		}

		return "proxy-console";
	}

	private static List<String> filter(List<String> options, String prefix) {
		String lower = prefix.toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();

		for (String option : options) {
			if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
				out.add(option);
			}
		}

		return out;
	}

	private static int asInt(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static String esc(Object value) {
		return MM.escapeTags(String.valueOf(value));
	}
}
