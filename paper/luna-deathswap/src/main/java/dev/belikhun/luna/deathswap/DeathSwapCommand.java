package dev.belikhun.luna.deathswap;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.deathswap.gui.DeathSwapMenus;
import dev.belikhun.luna.deathswap.lobby.LobbyService;
import dev.belikhun.luna.deathswap.room.Room;
import dev.belikhun.luna.deathswap.room.RoomManager;
import dev.belikhun.luna.deathswap.room.RoomSettings;
import dev.belikhun.luna.deathswap.world.RoundWorldService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /deathswap (/ds). Player commands at the top level; everything that
 * manages a room under /ds room, usable from the console with an explicit
 * room id, so a gamemaster (human or not) can run games without being in
 * one. Room owners may manage their own room; admins may manage any.
 */
public final class DeathSwapCommand implements BasicCommand {
	private static final String ADMIN = "lunadeathswap.admin";
	private static final String PLAY = "lunadeathswap.play";
	private static final String CREATE = "lunadeathswap.room.create";

	private static final List<String> ROOM_ACTIONS = List.of(
		"create", "delete", "list", "info", "set", "reset", "add", "kick", "sitout", "owner", "revive",
		"start", "stop", "swap", "say"
	);

	private final LunaDeathSwapPlugin plugin;
	private final RoomManager rooms;
	private final LobbyService lobby;
	private final DeathSwapStats stats;
	private final DeathSwapMenus menus;

	public DeathSwapCommand(LunaDeathSwapPlugin plugin, RoomManager rooms, LobbyService lobby, DeathSwapStats stats, DeathSwapMenus menus) {
		this.plugin = plugin;
		this.rooms = rooms;
		this.lobby = lobby;
		this.stats = stats;
		this.menus = menus;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

		switch (action) {
			case "rooms" -> {
				if (sender instanceof Player player) {
					menus.openBrowser(player, 0);
				} else {
					listRooms(sender);
				}
			}
			case "menu", "gui" -> {
				Player player = requirePlayer(sender, PLAY);
				if (player != null) {
					menus.openMain(player);
				}
			}
			case "settings", "config" -> {
				Player player = requirePlayer(sender, PLAY);
				if (player != null) {
					menus.openSettingsFor(player);
				}
			}
			case "top", "leaderboard" -> {
				Player player = requirePlayer(sender, PLAY);
				if (player != null) {
					menus.openLeaderboard(player);
				}
			}
			case "join" -> join(sender, args);
			case "quit", "leaveroom" -> quit(sender);
			case "sitout", "leave" -> sitOut(sender, true);
			case "ready" -> sitOut(sender, false);
			case "watch", "spectate" -> watch(sender, args);
			case "spec", "follow" -> spec(sender, args);
			case "status", "info" -> status(sender, args);
			case "profile", "stats" -> profile(sender);
			case "create" -> roomCreate(sender, args, 1);
			case "room" -> room(sender, args);
			case "setlobby" -> setLobby(sender);
			case "world", "worlds" -> world(sender, args);
			case "reload" -> reload(sender);
			default -> help(sender);
		}
	}

	// ------------------------------------------------------------------
	// player commands

	private void listRooms(CommandSender sender) {
		boolean admin = sender.hasPermission(ADMIN);
		DeathSwapText.send(sender, DeathSwapText.blockHeader("DANH SÁCH PHÒNG"));
		int shown = 0;
		for (Room room : rooms.rooms()) {
			if (room.settings().visibility == RoomSettings.Visibility.PRIVATE && !admin && !isMember(sender, room)) {
				continue;
			}
			DeathSwapText.send(sender, " <gray>▶</gray> " + room.tag() + " <gray>(" + DeathSwapText.escape(room.id()) + ")</gray> "
				+ room.stateDisplay() + DeathSwapText.DOT + "<white>" + room.members().size() + "/" + room.settings().maxPlayers + "</white>"
				+ (room.settings().visibility == RoomSettings.Visibility.PRIVATE ? " <gray>🔒</gray>" : ""));
			shown++;
		}
		if (shown == 0) {
			DeathSwapText.send(sender, " <gray>ℹ Chưa có phòng nào.</gray>");
		}
		if (sender.hasPermission(CREATE)) {
			DeathSwapText.send(sender, " <gray>Tạo phòng mới:</gray> " + DeathSwapText.command("/ds create <id> [tên]"));
		}
	}

	private void join(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender, PLAY);
		if (player == null) {
			return;
		}
		Room room = args.length >= 2 ? rooms.get(args[1]) : rooms.get(rooms.settings().defaultRoom());
		if (room == null) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Không tìm thấy phòng. Xem " + DeathSwapText.command("/ds rooms") + ".");
			return;
		}
		if (room.settings().visibility == RoomSettings.Visibility.PRIVATE && !player.hasPermission(ADMIN)
			&& !player.getUniqueId().equals(room.owner())) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Phòng này riêng tư; nhờ chủ phòng thêm bạn vào.");
			return;
		}
		switch (rooms.join(player, room)) {
			case JOINED -> DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đã vào phòng " + room.tag() + ".");
			case ALREADY -> {
				if (room.setSitOut(player.getUniqueId(), false)) {
					rooms.sendToLobbyIfThere(player);
					DeathSwapText.send(player, DeathSwapText.SUCCESS + "Bạn sẽ tham gia ván tiếp theo.");
				} else {
					DeathSwapText.send(player, DeathSwapText.INFO + "Bạn đã ở trong phòng " + room.tag() + ".");
				}
			}
			case FULL -> DeathSwapText.send(player, DeathSwapText.ERROR + "Phòng " + room.tag() + " đã đầy.");
		}
	}

	private void quit(CommandSender sender) {
		Player player = requirePlayer(sender, PLAY);
		if (player == null) {
			return;
		}
		Room room = rooms.roomOf(player);
		if (room == null) {
			DeathSwapText.send(player, DeathSwapText.INFO + "Bạn không ở trong phòng nào.");
			return;
		}
		if (room.state() == Room.State.PLAYING && room.isAlive(player.getUniqueId())) {
			DeathSwapText.send(player, DeathSwapText.WARN + "Bạn đang chơi; dùng " + DeathSwapText.command("/spawn") + " để bỏ cuộc trước.");
			return;
		}
		rooms.leave(player, "đã rời phòng.");
		DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đã rời phòng " + room.tag() + ".");
	}

	private void sitOut(CommandSender sender, boolean sitOut) {
		Player player = requirePlayer(sender, PLAY);
		if (player == null) {
			return;
		}
		Room room = rooms.roomOf(player);
		if (room == null) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Bạn chưa ở trong phòng nào. Xem " + DeathSwapText.command("/ds rooms") + ".");
			return;
		}
		boolean changed = room.setSitOut(player.getUniqueId(), sitOut);
		rooms.sendToLobbyIfThere(player);
		if (sitOut) {
			DeathSwapText.send(player, changed
				? DeathSwapText.SUCCESS + "Bạn sẽ " + DeathSwapText.white("ngồi ngoài") + " các ván sau. Quay lại bằng " + DeathSwapText.command("/ds ready") + "."
				: DeathSwapText.INFO + "Bạn đang ngồi ngoài rồi.");
		} else {
			DeathSwapText.send(player, changed
				? DeathSwapText.SUCCESS + "Bạn sẽ " + DeathSwapText.white("tham gia") + " ván tiếp theo."
				: DeathSwapText.INFO + "Bạn đã sẵn sàng rồi.");
		}
		rooms.refreshDisplay(player);
	}

	private void watch(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender, PLAY);
		if (player == null) {
			return;
		}
		Room room = args.length >= 2 ? rooms.get(args[1]) : rooms.roomOf(player);
		if (room == null) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Không tìm thấy phòng để xem.");
			return;
		}
		if (room.isAlive(player.getUniqueId())) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Bạn đang chơi mà!");
			return;
		}
		if (!room.spectate(player)) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Phòng " + room.tag() + " chưa có ván nào để xem.");
			return;
		}
		DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đang xem phòng " + room.tag() + ". Về sảnh bằng " + DeathSwapText.command("/spawn") + ".");
	}

	/** Follow one living player: /ds spec opens the picker, /ds spec <player> goes straight there. */
	private void spec(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender, PLAY);
		if (player == null) {
			return;
		}
		Room room = rooms.roomFor(player);
		Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
		if (args.length >= 2 && target != null && (room == null || !room.isAlive(target.getUniqueId()))) {
			Room targetRoom = rooms.roomOf(target);
			if (targetRoom != null && targetRoom.isAlive(target.getUniqueId())) {
				room = targetRoom;
			}
		}
		if (room == null || room.state() != Room.State.PLAYING) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Không có ván nào đang diễn ra để xem.");
			return;
		}
		if (args.length < 2) {
			menus.openSpectate(player, room.id());
			return;
		}
		if (target == null) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Không thấy người chơi " + DeathSwapText.white(DeathSwapText.escape(args[1])) + ".");
			return;
		}
		String error = room.spectatePlayer(player, target);
		if (error != null) {
			DeathSwapText.send(player, DeathSwapText.ERROR + DeathSwapText.escape(error));
		}
	}

	private void status(CommandSender sender, String[] args) {
		if (args.length < 2 && sender instanceof Player viewer) {
			menus.openMain(viewer);
			return;
		}
		Room room = args.length >= 2 ? rooms.get(args[1]) : (sender instanceof Player player ? rooms.roomFor(player) : null);
		if (room == null) {
			listRooms(sender);
			return;
		}
		info(sender, room, sender.hasPermission(ADMIN));
	}

	private void info(CommandSender sender, Room room, boolean full) {
		List<String> lines = new ArrayList<>();
		lines.add(DeathSwapText.blockHeader("PHÒNG") + " " + room.tag() + " <gray>(" + DeathSwapText.escape(room.id()) + ")</gray>");
		lines.add(DeathSwapText.blockLine("Trạng thái: " + room.stateDisplay() + DeathSwapText.DOT + "<white>Ván đã chơi: " + room.rounds() + "</white>"));
		lines.add(DeathSwapText.blockLine("Chủ phòng: " + DeathSwapText.white(ownerName(room))
			+ DeathSwapText.DOT + "<white>" + room.settings().visibility.name().toLowerCase(Locale.ROOT)
			+ (room.settings().persistent ? ", cố định" : ", tạm") + "</white>"));
		lines.add(DeathSwapText.blockLine("Thành viên (" + room.members().size() + "/" + room.settings().maxPlayers + "): <gray>"
			+ DeathSwapText.escape(String.join(", ", memberLabels(room))) + "</gray>"));
		switch (room.state()) {
			case STARTING -> lines.add(DeathSwapText.blockLine("Bắt đầu sau: " + DeathSwapText.white(room.countdownLeft() + "s")));
			case PREPARING -> lines.add(DeathSwapText.blockLine("Chuẩn bị: " + DeathSwapText.white(room.preparingStage())));
			case PLAYING, FINISHED -> {
				lines.add(DeathSwapText.blockLine("Còn sống: " + DeathSwapText.white(room.aliveCount() + "/" + room.participantCount())
					+ " <gray>" + DeathSwapText.escape(String.join(", ", room.aliveNames())) + "</gray>"));
				lines.add(DeathSwapText.blockLine("Hoán đổi: " + DeathSwapText.white("#" + room.swaps())
					+ DeathSwapText.DOT + "<white>Lần tới: " + (full ? DeathSwapText.clock(room.secondsUntilSwap()) : room.swapText()) + "</white>"
					+ DeathSwapText.DOT + "<white>Thời gian: " + DeathSwapText.clock(room.elapsedSeconds()) + "</white>"));
				if (full && room.world() != null) {
					lines.add(DeathSwapText.blockLine("Thế giới: <gray>" + DeathSwapText.escape(room.world().getName()) + "</gray>"));
				}
			}
			default -> {
			}
		}
		if (full) {
			StringBuilder settings = new StringBuilder();
			for (String key : RoomSettings.keys()) {
				if (key.equals("name")) {
					continue;
				}
				if (!settings.isEmpty()) {
					settings.append("<dark_gray>, </dark_gray>");
				}
				settings.append("<gray>").append(key).append("=</gray><white>").append(DeathSwapText.escape(room.settings().get(key))).append("</white>");
			}
			lines.add(DeathSwapText.blockLine("Thiết lập: " + settings));
		}
		for (String line : lines) {
			DeathSwapText.send(sender, line);
		}
	}

	private void profile(CommandSender sender) {
		Player player = requirePlayer(sender, PLAY);
		if (player == null) {
			return;
		}
		menus.openProfile(player, player.getUniqueId());
	}

	// ------------------------------------------------------------------
	// /ds room ...

	private void room(CommandSender sender, String[] args) {
		if (args.length < 2) {
			roomHelp(sender);
			return;
		}
		String action = args[1].toLowerCase(Locale.ROOT);
		if (action.equals("list")) {
			listRooms(sender);
			return;
		}
		if (action.equals("create")) {
			roomCreate(sender, args, 2);
			return;
		}
		if (!ROOM_ACTIONS.contains(action)) {
			roomHelp(sender);
			return;
		}
		if (args.length < 3) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Thiếu ID phòng: " + DeathSwapText.command("/ds room " + action + " <id> ..."));
			return;
		}
		Room room = rooms.get(args[2]);
		if (room == null) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Không có phòng " + DeathSwapText.white(args[2]) + ".");
			return;
		}
		if (!canManage(sender, room)) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Bạn không quản lý phòng này.");
			return;
		}
		String[] rest = Arrays.copyOfRange(args, 3, args.length);

		switch (action) {
			case "delete" -> {
				String reason = rest.length == 0 ? null : String.join(" ", rest);
				if (room.id().equals(rooms.settings().defaultRoom())) {
					DeathSwapText.send(sender, DeathSwapText.WARN + "Đây là phòng mặc định; nó sẽ được tạo lại khi tải lại cấu hình.");
				}
				rooms.delete(room.id(), reason);
				DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã đóng phòng " + DeathSwapText.white(room.id()) + ".");
			}
			case "info" -> info(sender, room, true);
			case "set" -> roomSet(sender, room, rest);
			case "reset" -> roomReset(sender, room, rest);
			case "add" -> roomAdd(sender, room, rest);
			case "kick" -> roomKick(sender, room, rest);
			case "sitout" -> roomSitOut(sender, room, rest);
			case "revive" -> {
				if (rest.length == 0) {
					DeathSwapText.send(sender, DeathSwapText.ERROR + "Cú pháp: " + DeathSwapText.command("/ds room revive " + room.id() + " <người chơi>"));
					return;
				}
				Player target = Bukkit.getPlayerExact(rest[0]);
				if (target == null) {
					DeathSwapText.send(sender, DeathSwapText.ERROR + "Không tìm thấy " + DeathSwapText.white(rest[0]) + " đang online.");
					return;
				}
				String error = room.revive(target);
				DeathSwapText.send(sender, error == null
					? DeathSwapText.SUCCESS + "Đã hồi sinh " + DeathSwapText.white(target.getName()) + " vào ván ở phòng " + DeathSwapText.white(room.id()) + "."
					: DeathSwapText.ERROR + error);
			}
			case "owner" -> roomOwner(sender, room, rest);
			case "start" -> {
				int seconds = 10;
				if (rest.length >= 1) {
					try {
						seconds = Integer.parseInt(rest[0]);
					} catch (NumberFormatException exception) {
						DeathSwapText.send(sender, DeathSwapText.ERROR + "Thời gian không hợp lệ: " + DeathSwapText.white(rest[0]));
						return;
					}
				}
				DeathSwapText.send(sender, room.forceStart(seconds)
					? DeathSwapText.SUCCESS + "Phòng " + DeathSwapText.white(room.id()) + ": đếm ngược " + Math.max(3, seconds) + "s."
					: DeathSwapText.ERROR + "Phòng đang có ván, không thể bắt đầu.");
			}
			case "stop" -> DeathSwapText.send(sender, room.stop(rest.length == 0 ? null : String.join(" ", rest))
				? DeathSwapText.SUCCESS + "Đã dừng ván ở phòng " + DeathSwapText.white(room.id()) + "."
				: DeathSwapText.ERROR + "Phòng không có ván nào để dừng.");
			case "swap" -> DeathSwapText.send(sender, room.forceSwap()
				? DeathSwapText.SUCCESS + "Đã hoán đổi ở phòng " + DeathSwapText.white(room.id()) + "."
				: DeathSwapText.ERROR + "Phòng chưa có ván nào đang chơi.");
			case "say" -> {
				if (rest.length == 0) {
					DeathSwapText.send(sender, DeathSwapText.ERROR + "Thiếu nội dung.");
					return;
				}
				room.broadcast(DeathSwapText.color(DeathSwapText.ACCENT_GOLD, "🔔") + " <white>" + DeathSwapText.escape(String.join(" ", rest)) + "</white>");
				DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã gửi tới phòng " + DeathSwapText.white(room.id()) + ".");
			}
			default -> roomHelp(sender);
		}
	}

	private void roomCreate(CommandSender sender, String[] args, int idIndex) {
		boolean admin = sender.hasPermission(ADMIN);
		if (!admin && !sender.hasPermission(CREATE)) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Bạn không có quyền tạo phòng.");
			return;
		}
		if (args.length <= idIndex) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Cú pháp: " + DeathSwapText.command("/ds create <id> [tên]"));
			return;
		}
		String id = args[idIndex].toLowerCase(Locale.ROOT);
		String name = args.length > idIndex + 1 ? String.join(" ", Arrays.copyOfRange(args, idIndex + 1, args.length)) : "";
		UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
		// rooms players make close when empty; rooms the console or an admin makes stay
		String error = rooms.create(id, owner, name, owner == null);
		if (error != null) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + DeathSwapText.escape(error));
			return;
		}
		Room room = rooms.get(id);
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã tạo phòng " + room.tag() + ". Chỉnh bằng "
			+ DeathSwapText.command("/ds room set " + id + " <thiết lập> <giá trị>") + ".");
		if (sender instanceof Player player) {
			rooms.join(player, room);
		}
	}

	private void roomSet(CommandSender sender, Room room, String[] rest) {
		if (rest.length < 2) {
			DeathSwapText.send(sender, DeathSwapText.blockHeader("THIẾT LẬP PHÒNG") + " <gray>/ds room set " + DeathSwapText.escape(room.id()) + " <khóa> <giá trị></gray>");
			for (RoomSettings.Option option : RoomSettings.OPTIONS.values()) {
				DeathSwapText.send(sender, " <gray>▶</gray> " + DeathSwapText.color(DeathSwapText.COMMAND, option.key())
					+ " <white>= " + DeathSwapText.escape(option.get().apply(room.settings())) + "</white> <gray>- "
					+ DeathSwapText.escape(option.description()) + "</gray>");
			}
			return;
		}
		String key = rest[0].toLowerCase(Locale.ROOT);
		String value = String.join(" ", Arrays.copyOfRange(rest, 1, rest.length));
		String error = rooms.changeSetting(sender, room, key, value);
		if (error != null) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + DeathSwapText.escape(error));
			return;
		}
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Phòng " + DeathSwapText.white(room.id()) + ": " + DeathSwapText.color(DeathSwapText.COMMAND, key)
			+ " = " + DeathSwapText.white(room.settings().get(key)) + (room.inRound() ? " <gray>(áp dụng từ ván sau nếu là thiết lập thế giới)</gray>" : ""));
	}

	private void roomReset(CommandSender sender, Room room, String[] rest) {
		RoomSettings defaults = rooms.settings().roomDefaults();
		List<String> keys = rest.length == 0 ? RoomSettings.keys() : List.of(rest[0].toLowerCase(Locale.ROOT));
		for (String key : keys) {
			if (key.equals("name") || key.equals("persistent") || key.equals("visibility")) {
				if (rest.length == 0) {
					continue;
				}
			}
			String error = room.settings().set(key, defaults.get(key) == null ? "" : defaults.get(key));
			if (error != null) {
				DeathSwapText.send(sender, DeathSwapText.ERROR + DeathSwapText.escape(key + ": " + error));
				return;
			}
		}
		rooms.save();
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã đặt lại " + (rest.length == 0 ? "các thiết lập ván" : DeathSwapText.white(rest[0]))
			+ " của phòng " + DeathSwapText.white(room.id()) + " về mặc định.");
	}

	private void roomAdd(CommandSender sender, Room room, String[] rest) {
		if (rest.length == 0) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Cú pháp: " + DeathSwapText.command("/ds room add " + room.id() + " <người chơi...|@a>"));
			return;
		}
		List<Player> targets = resolvePlayers(rest);
		if (targets.isEmpty()) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Không tìm thấy người chơi nào đang online.");
			return;
		}
		List<String> added = new ArrayList<>();
		for (Player target : targets) {
			Room current = rooms.roomOf(target);
			if (current != null && current != room && current.isAlive(target.getUniqueId())) {
				DeathSwapText.send(sender, DeathSwapText.WARN + DeathSwapText.white(target.getName()) + " đang chơi ở phòng " + DeathSwapText.white(current.id()) + ", bỏ qua.");
				continue;
			}
			Room.JoinResult result = rooms.join(target, room);
			if (result == Room.JoinResult.JOINED) {
				added.add(target.getName());
				DeathSwapText.send(target, DeathSwapText.INFO + "Bạn đã được thêm vào phòng " + room.tag() + ".");
			} else if (result == Room.JoinResult.FULL) {
				DeathSwapText.send(sender, DeathSwapText.WARN + "Phòng đã đầy, dừng ở " + DeathSwapText.white(target.getName()) + ".");
				break;
			}
		}
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã thêm " + added.size() + " người vào phòng " + DeathSwapText.white(room.id())
			+ (added.isEmpty() ? "." : ": <gray>" + DeathSwapText.escape(String.join(", ", added)) + "</gray>"));
	}

	private void roomKick(CommandSender sender, Room room, String[] rest) {
		if (rest.length == 0) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Cú pháp: " + DeathSwapText.command("/ds room kick " + room.id() + " <người chơi> [lí do]"));
			return;
		}
		Player target = Bukkit.getPlayerExact(rest[0]);
		if (target == null || rooms.roomOf(target) != room) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + DeathSwapText.white(rest[0]) + " không ở trong phòng này.");
			return;
		}
		String reason = rest.length > 1 ? String.join(" ", Arrays.copyOfRange(rest, 1, rest.length)) : null;
		rooms.leave(target, reason == null ? "đã bị mời ra khỏi phòng." : "đã bị mời ra: " + DeathSwapText.escape(reason));
		DeathSwapText.send(target, DeathSwapText.WARN + "Bạn đã bị mời ra khỏi phòng " + room.tag() + (reason == null ? "." : ": " + DeathSwapText.white(reason)));
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã mời " + DeathSwapText.white(target.getName()) + " ra khỏi phòng.");
	}

	private void roomSitOut(CommandSender sender, Room room, String[] rest) {
		if (rest.length == 0) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Cú pháp: " + DeathSwapText.command("/ds room sitout " + room.id() + " <người chơi> [on|off]"));
			return;
		}
		Player target = Bukkit.getPlayerExact(rest[0]);
		if (target == null || !room.isMember(target.getUniqueId())) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + DeathSwapText.white(rest[0]) + " không ở trong phòng này.");
			return;
		}
		boolean sitOut = rest.length < 2 ? !room.isSittingOut(target.getUniqueId()) : !rest[1].equalsIgnoreCase("off");
		room.setSitOut(target.getUniqueId(), sitOut);
		rooms.sendToLobbyIfThere(target);
		rooms.refreshDisplay(target);
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + DeathSwapText.white(target.getName()) + (sitOut ? " sẽ ngồi ngoài." : " sẽ tham gia."));
	}

	private void roomOwner(CommandSender sender, Room room, String[] rest) {
		if (rest.length == 0) {
			DeathSwapText.send(sender, DeathSwapText.INFO + "Chủ phòng: " + DeathSwapText.white(ownerName(room)));
			return;
		}
		if (rest[0].equalsIgnoreCase("none")) {
			rooms.setOwner(room, null);
			DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Phòng " + DeathSwapText.white(room.id()) + " không còn chủ.");
			return;
		}
		Player target = Bukkit.getPlayerExact(rest[0]);
		if (target == null) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Không tìm thấy " + DeathSwapText.white(rest[0]) + ".");
			return;
		}
		rooms.setOwner(room, target.getUniqueId());
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Chủ phòng " + DeathSwapText.white(room.id()) + " giờ là " + DeathSwapText.white(target.getName()) + ".");
	}

	private void roomHelp(CommandSender sender) {
		DeathSwapText.send(sender, DeathSwapText.blockHeader("QUẢN LÝ PHÒNG") + " <gray>(/ds room)</gray>");
		helpLine(sender, "/ds room create <id> [tên]", "Tạo phòng");
		helpLine(sender, "/ds room list", "Liệt kê phòng");
		helpLine(sender, "/ds room info <id>", "Thông tin và thiết lập");
		helpLine(sender, "/ds room set <id> [khóa] [giá trị]", "Xem hoặc đổi thiết lập");
		helpLine(sender, "/ds room reset <id> [khóa]", "Đặt lại về mặc định");
		helpLine(sender, "/ds room add <id> <người...|@a>", "Thêm người chơi");
		helpLine(sender, "/ds room kick <id> <người> [lí do]", "Mời ra khỏi phòng");
		helpLine(sender, "/ds room sitout <id> <người> [on|off]", "Cho ngồi ngoài");
		helpLine(sender, "/ds room revive <id> <người>", "Hồi sinh người đã bị loại vào ván đang chơi");
		helpLine(sender, "/ds room owner <id> [người|none]", "Đổi chủ phòng");
		helpLine(sender, "/ds room start <id> [giây]", "Bắt đầu ván");
		helpLine(sender, "/ds room stop <id> [lí do]", "Dừng ván");
		helpLine(sender, "/ds room swap <id>", "Hoán đổi ngay");
		helpLine(sender, "/ds room say <id> <nội dung>", "Thông báo cho phòng");
		helpLine(sender, "/ds room delete <id> [lí do]", "Đóng phòng");
	}

	// ------------------------------------------------------------------
	// server-wide admin

	private void setLobby(CommandSender sender) {
		Player player = requirePlayer(sender, ADMIN);
		if (player == null) {
			return;
		}
		if (RoundWorldService.isRoundWorld(player.getWorld())) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Không thể đặt sảnh trong thế giới của ván đấu.");
			return;
		}
		lobby.setSpawn(player.getLocation());
		DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đã đặt điểm sảnh tại vị trí của bạn.");
	}

	private void world(CommandSender sender, String[] args) {
		if (!requireAdmin(sender)) {
			return;
		}
		String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
		RoundWorldService worlds = rooms.worlds();
		switch (sub) {
			case "generate", "gen" -> DeathSwapText.send(sender, worlds.generateNow()
				? DeathSwapText.SUCCESS + "Đã bắt đầu tạo một thế giới mới (khoảng " + DeathSwapText.white(DeathSwapText.clock(worlds.expectedGenerationSeconds())) + ")."
				: DeathSwapText.ERROR + "Máy tạo thế giới đã tắt.");
			case "cleanup" -> DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã dọn " + DeathSwapText.white(String.valueOf(worlds.cleanupNow())) + " thế giới thừa.");
			case "test" -> worldTest(sender, worlds);
			default -> {
				RoundWorldService.Status status = worlds.status();
				DeathSwapText.send(sender, DeathSwapText.blockHeader("THẾ GIỚI VÁN ĐẤU"));
				DeathSwapText.send(sender, DeathSwapText.blockLine("Sẵn sàng: " + DeathSwapText.white(status.ready() + "/" + status.poolSize())
					+ DeathSwapText.DOT + "<white>Tạo mất khoảng </white>" + DeathSwapText.white(DeathSwapText.clock(worlds.expectedGenerationSeconds()))));
				if (status.busy()) {
					DeathSwapText.send(sender, DeathSwapText.blockLine("Đang chạy: " + DeathSwapText.white(status.stageLabel())
						+ " <gray>(" + Math.round(status.fraction() * 100) + "%, còn ~" + DeathSwapText.clock(status.etaSeconds()) + ")</gray>"));
				}
				for (String line : worlds.describe()) {
					DeathSwapText.send(sender, " <gray>▶</gray> <white>" + DeathSwapText.escape(line) + "</white>");
				}
			}
		}
	}

	/** Take a world, prove it loaded and generates chunks, give it back: the whole cycle with nobody in it. */
	private void worldTest(CommandSender sender, RoundWorldService worlds) {
		DeathSwapText.send(sender, DeathSwapText.WAIT + "Đang thử: lấy thế giới, nạp chunk, trả lại...");
		long started = System.currentTimeMillis();
		worlds.acquire(0L).whenComplete((world, error) -> {
			if (world == null) {
				DeathSwapText.send(sender, DeathSwapText.ERROR + "Không lấy được thế giới" + (error == null ? "." : ": " + DeathSwapText.escape(String.valueOf(error.getMessage()))));
				return;
			}
			long acquiredMs = System.currentTimeMillis() - started;
			world.getChunkAtAsync(1500 >> 4, 0).whenComplete((chunk, chunkError) -> Bukkit.getScheduler().runTask(plugin, () -> {
				int top = chunk == null ? -999 : world.getHighestBlockYAt(1500, 0);
				DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Thế giới " + DeathSwapText.white(world.getName())
					+ " key=" + DeathSwapText.white(world.getKey().toString()) + " lấy trong " + acquiredMs + "ms, chunk (1500,0) cao " + top
					+ ", thư mục " + DeathSwapText.white(world.getWorldFolder().getPath()) + ".");
				worlds.release(world);
				DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã trả lại; xem " + DeathSwapText.command("/ds world") + " để thấy nó bị xóa.");
			}));
		});
	}

	private void reload(CommandSender sender) {
		if (!requireAdmin(sender)) {
			return;
		}
		plugin.reloadSettings();
		DeathSwapText.send(sender, DeathSwapText.SUCCESS + "Đã tải lại cấu hình LunaDeathSwap.");
	}

	private void help(CommandSender sender) {
		DeathSwapText.send(sender, DeathSwapText.blockHeader("DEATHSWAP") + " <gray>- Lệnh</gray>");
		helpLine(sender, "/ds menu", "Mở bảng điều khiển");
		helpLine(sender, "/ds rooms", "Danh sách phòng");
		helpLine(sender, "/ds join [phòng]", "Vào phòng");
		helpLine(sender, "/ds quit", "Rời phòng");
		helpLine(sender, "/ds sitout | /ds ready", "Ngồi ngoài / sẵn sàng");
		helpLine(sender, "/ds watch [phòng]", "Xem ván đang diễn ra");
		helpLine(sender, "/ds spec [người chơi]", "Theo dõi một người chơi");
		helpLine(sender, "/ds status [phòng]", "Tình hình phòng");
		helpLine(sender, "/ds profile", "Thành tích của bạn");
		helpLine(sender, "/ds top", "Bảng xếp hạng");
		helpLine(sender, "/ds settings", "Thiết lập phòng của bạn");
		helpLine(sender, "/ds create <id> [tên]", "Tạo phòng của bạn");
		helpLine(sender, "/spawn", "Về sảnh");
		if (sender.hasPermission(ADMIN)) {
			helpLine(sender, "/ds room", "Quản lý phòng");
			helpLine(sender, "/ds setlobby", "Đặt điểm sảnh tại chỗ");
			helpLine(sender, "/ds world [generate|cleanup|test]", "Quản lý thế giới");
			helpLine(sender, "/ds reload", "Tải lại cấu hình");
		}
	}

	// ------------------------------------------------------------------
	// helpers

	private static void helpLine(CommandSender sender, String command, String description) {
		DeathSwapText.send(sender, " <gray>▶</gray> " + DeathSwapText.command(command) + " <gray>- " + description + "</gray>");
	}

	private boolean canManage(CommandSender sender, Room room) {
		return rooms.canManage(sender, room);
	}

	private boolean isMember(CommandSender sender, Room room) {
		return sender instanceof Player player && room.isMember(player.getUniqueId());
	}

	private static String ownerName(Room room) {
		if (room.owner() == null) {
			return "không có";
		}
		String name = Bukkit.getOfflinePlayer(room.owner()).getName();
		return name == null ? room.owner().toString().substring(0, 8) : name;
	}

	private List<String> memberLabels(Room room) {
		List<String> labels = new ArrayList<>();
		for (UUID playerId : room.members()) {
			Player player = Bukkit.getPlayer(playerId);
			String name = player == null ? playerId.toString().substring(0, 8) : player.getName();
			if (room.isAlive(playerId)) {
				name += " ❤";
			} else if (room.isSittingOut(playerId)) {
				name += " (ngồi ngoài)";
			}
			labels.add(name);
		}
		return labels;
	}

	private static List<Player> resolvePlayers(String[] names) {
		List<Player> out = new ArrayList<>();
		for (String name : names) {
			if (name.equals("@a")) {
				for (Player online : Bukkit.getOnlinePlayers()) {
					if (!out.contains(online)) {
						out.add(online);
					}
				}
				continue;
			}
			Player player = Bukkit.getPlayerExact(name);
			if (player != null && !out.contains(player)) {
				out.add(player);
			}
		}
		return out;
	}

	private static Player requirePlayer(CommandSender sender, String permission) {
		if (!(sender instanceof Player player)) {
			DeathSwapText.send(sender, DeathSwapText.ERROR + "Chỉ người chơi mới dùng được lệnh này.");
			return null;
		}
		if (!player.hasPermission(permission)) {
			DeathSwapText.send(player, DeathSwapText.ERROR + "Bạn không có quyền dùng lệnh này.");
			return null;
		}
		return player;
	}

	private static boolean requireAdmin(CommandSender sender) {
		if (sender.hasPermission(ADMIN)) {
			return true;
		}
		DeathSwapText.send(sender, DeathSwapText.ERROR + "Bạn không có quyền dùng lệnh này.");
		return false;
	}

	// ------------------------------------------------------------------
	// tab completion

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		boolean admin = sender.hasPermission(ADMIN);
		if (args.length <= 1) {
			List<String> options = new ArrayList<>(List.of("menu", "rooms", "join", "quit", "sitout", "ready", "watch", "spec", "status", "profile", "top", "settings", "create", "help"));
			options.add("room");
			if (admin) {
				options.addAll(List.of("setlobby", "world", "reload"));
			}
			return CommandCompletions.filterPrefix(options, args.length == 0 ? "" : args[0]);
		}

		String first = args[0].toLowerCase(Locale.ROOT);
		if (args.length == 2 && List.of("join", "watch", "status").contains(first)) {
			return CommandCompletions.filterPrefix(roomIds(), args[1]);
		}
		if (args.length == 2 && List.of("spec", "follow").contains(first)) {
			List<String> names = new ArrayList<>();
			for (Room room : rooms.rooms()) {
				for (Player alive : room.spectatable()) {
					names.add(alive.getName());
				}
			}
			return CommandCompletions.filterPrefix(names, args[1]);
		}
		if (args.length == 2 && first.equals("world") && admin) {
			return CommandCompletions.filterPrefix(List.of("status", "generate", "cleanup", "test"), args[1]);
		}
		if (!first.equals("room")) {
			return List.of();
		}

		if (args.length == 2) {
			return CommandCompletions.filterPrefix(ROOM_ACTIONS, args[1]);
		}
		String action = args[1].toLowerCase(Locale.ROOT);
		if (args.length == 3 && !action.equals("create") && !action.equals("list")) {
			return CommandCompletions.filterPrefix(roomIds(), args[2]);
		}
		if (args.length == 4 && (action.equals("set") || action.equals("reset"))) {
			return CommandCompletions.filterPrefix(RoomSettings.keys(), args[3]);
		}
		if (args.length == 5 && action.equals("set")) {
			RoomSettings.Option option = RoomSettings.OPTIONS.get(args[3].toLowerCase(Locale.ROOT));
			return option == null ? List.of() : CommandCompletions.filterPrefix(option.suggestions(), args[4]);
		}
		if (args.length >= 4 && List.of("add", "kick", "sitout", "owner", "revive").contains(action)) {
			if (args.length == 5 && action.equals("sitout")) {
				return CommandCompletions.filterPrefix(List.of("on", "off"), args[4]);
			}
			List<String> names = new ArrayList<>();
			if (action.equals("add")) {
				names.add("@a");
			}
			if (action.equals("owner")) {
				names.add("none");
			}
			for (Player online : Bukkit.getOnlinePlayers()) {
				names.add(online.getName());
			}
			return CommandCompletions.filterPrefix(names, args[args.length - 1]);
		}
		if (args.length == 4 && action.equals("start")) {
			return CommandCompletions.filterPrefix(List.of("5", "10", "30", "60"), args[3]);
		}
		return List.of();
	}

	private List<String> roomIds() {
		List<String> ids = new ArrayList<>();
		for (Room room : rooms.rooms()) {
			ids.add(room.id());
		}
		return ids;
	}
}
