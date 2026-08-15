package dev.belikhun.luna.core.mc12.command;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.placeholder.LegacyPlaceholderService;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.heartbeat.BackendHeartbeatPublisher;
import dev.belikhun.luna.legacy.heartbeat.BackendServerStatus;
import dev.belikhun.luna.legacy.heartbeat.BackendStatusView;
import dev.belikhun.luna.legacy.permission.MirroredPermissionService;
import dev.belikhun.luna.legacy.permission.PermissionSnapshot;
import dev.belikhun.luna.legacy.permission.Tristate;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * `/luna` on 1.12.2.
 *
 * Small on purpose: this is milestone 1, so the command exists to make the two things
 * the milestone actually built observable from inside the game - the heartbeat is
 * running, and permissions resolve against the proxy's LuckPerms. Feature commands
 * arrive with their own modules.
 */
public final class LunaCommand extends CommandBase {
	/** The node `/luna perms <other>` needs. Nobody has it until it is granted. */
	/** The fleet's core-admin node: paper's plugin.yml and velocity both gate on it. */
	private static final String ADMIN_NODE = "lunacore.admin";

	private final MirroredPermissionService permissions;
	private final BackendHeartbeatPublisher heartbeat;
	private final BackendStatusView network;

	public LunaCommand(MirroredPermissionService permissions, BackendHeartbeatPublisher heartbeat, BackendStatusView network) {
		this.permissions = permissions;
		this.heartbeat = heartbeat;
		this.network = network;
	}

	/**
	 * `lunacoreforge`, following the family: one core command per platform.
	 *
	 * Paper registers `lunacorepaper` (alias `lcp`) and velocity `lunacoreproxy`
	 * (aliases `lcv`, `luna`). Naming it `luna` here looked shorter and was
	 * unreachable: velocity owns that alias and answers it without forwarding, so
	 * the command worked from the server console and printed the proxy's help to
	 * every player who tried it.
	 */
	@Override
	public String getName() {
		return "lunacoreforge";
	}

	@Override
	public List<String> getAliases() {
		List<String> aliases = new ArrayList<String>();

		aliases.add("lcf");

		return aliases;
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/lunacoreforge [status|perms [player]|papi <placeholder>]";
	}

	/**
	 * Zero, not the usual 2.
	 *
	 * `CommandBase` defaults to requiring op, and 1.12.2 checks this *before* the
	 * command runs - so leaving it at 2 would gate `/luna status` behind op and make
	 * the permission mirror unreachable for exactly the non-op accounts it exists to
	 * answer for. Each subcommand does its own check instead.
	 */
	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	@Override
	public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
		return true;
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, net.minecraft.util.math.BlockPos pos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, "status", "perms", "papi");
		}

		if (args.length == 2 && "perms".equalsIgnoreCase(args[0])) {
			return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
		}

		return new ArrayList<String>();
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
		String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);

		if ("status".equals(subcommand)) {
			status(sender);
			return;
		}

		if ("perms".equals(subcommand)) {
			perms(server, sender, Arrays.copyOfRange(args, 1, args.length));
			return;
		}

		if ("papi".equals(subcommand)) {
			papi(sender, Arrays.copyOfRange(args, 1, args.length));
			return;
		}

		reply(sender, "<red>Không rõ lệnh con: <white>" + subcommand + "<red>. " + getUsage(sender));
	}

	/**
	 * Resolve a placeholder for the caller and show what came back.
	 *
	 * Nothing else on this line makes a placeholder visible: they are answered for
	 * the proxy, not for chat, so a value that resolves wrongly is invisible until
	 * it reaches a tab list somewhere else. Typing the identifier here asks the same
	 * service, for the same player, and prints the raw result.
	 *
	 * A miss is shown as such rather than as an empty line: an identifier nothing
	 * claims and an identifier that resolves to "" are different faults, and telling
	 * them apart is the whole point of asking.
	 */
	private void papi(ICommandSender sender, String[] args) {
		if (!(sender instanceof EntityPlayerMP)) {
			reply(sender, "<red>Lệnh này cần chạy từ trong game.");
			return;
		}

		if (args.length == 0) {
			reply(sender, "<yellow>Cú pháp: <white>/lunacoreforge papi %luna_tps%");
			return;
		}

		EntityPlayerMP caller = (EntityPlayerMP) sender;

		if (!permissions.hasPermission(caller.getUniqueID(), ADMIN_NODE)) {
			reply(sender, "<red>Bạn không có quyền <white>" + ADMIN_NODE + "<red>.");
			return;
		}

		LegacyPlaceholderService placeholders = LunaCore.find(LegacyPlaceholderService.class);

		if (placeholders == null) {
			reply(sender, "<red>Placeholder service chưa sẵn sàng.");
			return;
		}

		String identifier = String.join(" ", args).trim();
		String value = placeholders.resolvePlaceholder(caller, identifier);

		reply(sender, "<gray>Placeholder: <white>" + identifier);

		if (value == null) {
			reply(sender, "<red>→ không có provider nào nhận identifier này.");
			return;
		}

		reply(sender, "<green>→ <white>" + value + "<gray> (" + value.length() + " ký tự)");
	}

	private void status(ICommandSender sender) {
		reply(sender, "<aqua>LunaCore <gray>· <white>Forge 1.12.2");
		reply(sender, "<gray>Heartbeat: " + (heartbeat == null ? "<red>tắt" : "<green>đang chạy"));
		reply(sender, "<gray>Permission mirror: " + (permissions.isAvailable()
			? "<green>sẵn sàng <gray>(" + permissions.cachedCount() + " người chơi đã đồng bộ)"
			: "<yellow>chưa cấu hình"));
		reply(sender, "<gray>Registry mirror: " + describeNetwork());
	}

	/**
	 * What this backend knows about the rest of the cluster.
	 *
	 * The server selector draws from this mirror, so an empty one is the difference
	 * between a menu showing live state and a menu showing every server offline -
	 * and from inside the menu those look the same. This says which it is.
	 */
	private String describeNetwork() {
		if (network == null) {
			return "<red>không có";
		}

		Map<String, BackendServerStatus> snapshot = network.snapshot();

		if (snapshot.isEmpty()) {
			return "<yellow>trống <gray>(chưa nhận được hàng nào từ proxy)";
		}

		int online = 0;

		for (BackendServerStatus status : snapshot.values()) {
			if (status != null && status.online()) {
				online += 1;
			}
		}

		return "<green>" + online + "<gray>/<white>" + snapshot.size() + " <gray>máy chủ đang online";
	}

	private void perms(MinecraftServer server, ICommandSender sender, String[] args) {
		EntityPlayerMP self = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;

		if (args.length == 0) {
			if (self == null) {
				// square brackets, not angle: every reply goes through MiniMessage, and
				// <tên> would be read as a tag rather than as a placeholder
				reply(sender, "<red>Console phải nêu tên người chơi: <white>/luna perms [tên]");
				return;
			}

			describe(sender, permissions.snapshot(self.getUniqueID()), self.getName());
			return;
		}

		// reading someone else's permissions is an admin verb; reading your own is not
		if (self != null && !permissions.hasPermission(self.getUniqueID(), ADMIN_NODE)) {
			reply(sender, "<red>Bạn không có quyền <white>" + ADMIN_NODE + "<red>.");
			return;
		}

		// an online player is looked up by uuid, but the proxy resolves a name just as
		// well - and an admin asking about someone is usually asking while they are
		// offline, which is exactly when a name is all there is
		EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(args[0]);

		if (online != null) {
			describe(sender, permissions.snapshot(online.getUniqueID()), online.getName());
			return;
		}

		PermissionSnapshot cached = permissions.snapshotByName(args[0]);

		if (cached == null) {
			permissions.warmByName(args[0]);
		}

		describe(sender, cached, args[0]);
	}

	private void describe(ICommandSender sender, PermissionSnapshot snapshot, String username) {
		if (snapshot == null) {
			// a cold entry is not an error: the lookup that just missed queued the fetch
			reply(sender, "<yellow>Chưa có dữ liệu quyền của <white>" + username
				+ "<yellow>; đang lấy từ proxy, thử lại sau vài giây.");
			return;
		}

		reply(sender, "<aqua>Quyền của <white>" + snapshot.username() + " <gray>(nhóm chính: <white>"
			+ snapshot.primaryGroup() + "<gray>)");
		reply(sender, "<gray>Nhóm: <white>" + joinNames(snapshot.groups()));
		reply(sender, "<gray>Số node đã giải: <white>" + snapshot.permissions().size());

		// the node this very command gates on, so an op and a non-op visibly differ
		Tristate admin = snapshot.check(ADMIN_NODE);
		reply(sender, "<gray>" + ADMIN_NODE + ": " + describeTristate(admin));

		int shown = 0;

		for (Map.Entry<String, Boolean> entry : snapshot.permissions().entrySet()) {
			if (shown >= 10) {
				reply(sender, "<dark_gray>… và " + (snapshot.permissions().size() - shown) + " node nữa.");
				break;
			}

			reply(sender, (entry.getValue().booleanValue() ? "  <green>+ " : "  <red>- ") + "<gray>" + entry.getKey());
			shown += 1;
		}
	}

	private static String describeTristate(Tristate value) {
		if (value == Tristate.TRUE) {
			return "<green>có";
		}

		if (value == Tristate.FALSE) {
			return "<red>bị từ chối";
		}

		return "<yellow>chưa đặt";
	}

	private static String joinNames(List<String> values) {
		if (values.isEmpty()) {
			return "—";
		}

		StringBuilder out = new StringBuilder();

		for (String value : values) {
			if (out.length() > 0) {
				out.append(", ");
			}

			out.append(value);
		}

		return out.toString();
	}

	private static void reply(ICommandSender sender, String miniMessage) {
		sender.sendMessage(LunaTextComponents.mini(miniMessage));
	}
}
