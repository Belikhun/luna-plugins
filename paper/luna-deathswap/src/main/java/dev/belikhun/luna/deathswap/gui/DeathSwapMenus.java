package dev.belikhun.luna.deathswap.gui;

import dev.belikhun.luna.core.api.gui.AnvilInputManager;
import dev.belikhun.luna.core.api.gui.GuiClickHandler;
import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.gui.NumberSelectorGui;
import dev.belikhun.luna.core.api.ui.LunaLore;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.deathswap.DeathSwapStats;
import dev.belikhun.luna.deathswap.DeathSwapText;
import dev.belikhun.luna.deathswap.LunaDeathSwapPlugin;
import dev.belikhun.luna.deathswap.lobby.SpawnCommand;
import dev.belikhun.luna.deathswap.room.Room;
import dev.belikhun.luna.deathswap.room.RoomManager;
import dev.belikhun.luna.deathswap.room.RoomSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every inventory screen of DeathSwap, in LunaManhunt's layout language:
 * actions along the bottom row (create at 45, refresh at 49, close at 53),
 * a black glass frame around settings, setting rows of "reset head, spacer,
 * up to five option buttons" with the current choice glinting, and a 27-slot
 * profile. Built on luna-core's GuiView/GuiManager, so click handling,
 * drag-blocking and cleanup are the shared ones.
 *
 * Every action calls the same RoomManager / Room verbs as the commands, so the
 * GUI and the console can never disagree. Live screens (room list, room panel,
 * invite list) re-render in place once a second while open.
 */
public final class DeathSwapMenus implements Listener {
	private static final GuiClickHandler NOOP = (player, event, view) -> {
	};
	private static final int[] HEAD_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
	private static final int[] ROW_BASES = {10, 19, 28, 37};
	private static final int SETTING_ROWS_PER_PAGE = ROW_BASES.length;
	private static final Set<String> SECONDS = Set.of("start-countdown", "first-swap-min", "first-swap-max", "swap-min", "swap-max", "swap-warning", "trap-credit", "end-delay");

	private static final Map<String, String> LABELS = new LinkedHashMap<>();
	private static final Map<String, Material> ICONS = new HashMap<>();
	private static final Map<String, int[]> STEPS = new HashMap<>();

	static {
		label("name", "Tên phòng", Material.NAME_TAG);
		label("visibility", "Hiển thị", Material.OAK_DOOR);
		label("persistent", "Phòng cố định", Material.ANVIL);
		label("max-players", "Số người tối đa", Material.ARMOR_STAND);
		label("min-players", "Số người để bắt đầu", Material.LEAD);
		label("auto-start", "Tự bắt đầu", Material.REPEATER);
		label("start-countdown", "Đếm ngược", Material.CLOCK);
		label("swap-min", "Hoán đổi sớm nhất", Material.ENDER_PEARL);
		label("swap-max", "Hoán đổi muộn nhất", Material.ENDER_EYE);
		label("swap-warning", "Cảnh báo hoán đổi", Material.BELL);
		label("first-swap-min", "Lần đổi đầu sớm nhất", Material.SNIFFER_EGG);
		label("first-swap-max", "Lần đổi đầu muộn nhất", Material.TURTLE_EGG);
		label("hide-first-swap", "Ẩn giờ lần đầu", Material.TINTED_GLASS);
		label("show-timer", "Hiện giờ hoán đổi", Material.SPYGLASS);
		label("swap-bossbar", "Thanh đếm hoán đổi", Material.DRAGON_HEAD);
		label("locator-bar", "Thanh định vị", Material.RECOVERY_COMPASS);
		label("vague-timer", "Giờ mơ hồ", Material.COMPASS);
		label("swap-pattern", "Cách hoán đổi", Material.CHORUS_FRUIT);
		label("kit", "Đồ khởi đầu", Material.CHEST);
		label("max-hearts", "Số tim tối đa", Material.GOLDEN_APPLE);
		label("lifeswap", "LifeSwap (cướp tim)", Material.TOTEM_OF_UNDYING);
		label("lifeswap-steal", "Tim bị cướp mỗi lần chết", Material.REDSTONE);
		label("swap-drain", "Tim mất mỗi lần đổi", Material.FERMENTED_SPIDER_EYE);
		label("shrink-after", "Thu hẹp sau (phút)", Material.HOPPER);
		label("shrink-time", "Thời gian thu hẹp (phút)", Material.HONEYCOMB);
		label("shrink-size", "Vùng chơi cuối (ô)", Material.STRUCTURE_VOID);
		label("shrink-pvp", "PvP khi thu hẹp", Material.GOLDEN_SWORD);
		label("shrink-final-time", "Thu nốt về 0 (phút)", Material.CLOCK);
		label("shrink-teleport", "Đưa vào đấu trường", Material.END_PORTAL_FRAME);
		label("shrink-sudden-death", "Đấu trường: chết là loại", Material.WITHER_SKELETON_SKULL);
		label("arena-size", "Đấu trường (ô)", Material.TARGET);
		label("keep-momentum", "Giữ đà rơi", Material.FEATHER);
		label("spread-radius", "Bán kính rải người", Material.COMPASS);
		label("world-border", "Viền thế giới", Material.IRON_BARS);
		label("difficulty", "Độ khó", Material.IRON_SWORD);
		label("pvp", "Đánh nhau trực tiếp", Material.DIAMOND_SWORD);
		label("trap-credit", "Thời gian tính bẫy", Material.TRIPWIRE_HOOK);
		label("end-delay", "Xem kết quả", Material.FIREWORK_ROCKET);
		label("seed", "Seed thế giới", Material.WHEAT_SEEDS);

		STEPS.put("max-players", new int[] {5, 1});
		STEPS.put("min-players", new int[] {5, 1});
		STEPS.put("start-countdown", new int[] {30, 5});
		STEPS.put("first-swap-min", new int[] {60, 10});
		STEPS.put("first-swap-max", new int[] {60, 10});
		STEPS.put("swap-min", new int[] {60, 10});
		STEPS.put("swap-max", new int[] {60, 10});
		STEPS.put("swap-warning", new int[] {5, 1});
		STEPS.put("trap-credit", new int[] {30, 5});
		STEPS.put("end-delay", new int[] {5, 1});
		STEPS.put("max-hearts", new int[] {5, 1});
		STEPS.put("shrink-after", new int[] {10, 1});
		STEPS.put("shrink-time", new int[] {5, 1});
		STEPS.put("shrink-size", new int[] {100, 10});
		STEPS.put("arena-size", new int[] {100, 50});
		STEPS.put("lifeswap-steal", new int[] {2, 1});
		STEPS.put("spread-radius", new int[] {500, 100});
		STEPS.put("world-border", new int[] {1000, 250});
	}

	private static void label(String key, String text, Material icon) {
		LABELS.put(key, text);
		ICONS.put(key, icon);
	}

	/** A screen that keeps itself current while it is the one open. */
	private record Live(GuiView view, Runnable render) {
	}

	private final LunaDeathSwapPlugin plugin;
	private final RoomManager rooms;
	private final DeathSwapStats stats;
	private final GuiManager guis = new GuiManager();
	private final NumberSelectorGui numbers;
	private final AnvilInputManager anvil;
	private final Map<UUID, Live> live = new HashMap<>();
	private SpawnCommand spawn;
	private BukkitTask ticker;

	public DeathSwapMenus(LunaDeathSwapPlugin plugin, RoomManager rooms, DeathSwapStats stats) {
		this.plugin = plugin;
		this.rooms = rooms;
		this.stats = stats;
		this.numbers = new NumberSelectorGui(plugin, guis);
		this.anvil = new AnvilInputManager(plugin);
	}

	public void setSpawnCommand(SpawnCommand spawn) {
		this.spawn = spawn;
	}

	public void enable() {
		var manager = plugin.getServer().getPluginManager();
		manager.registerEvents(guis, plugin);
		manager.registerEvents(numbers, plugin);
		manager.registerEvents(anvil, plugin);
		manager.registerEvents(this, plugin);
		ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLive, 20L, 20L);
	}

	public void disable() {
		if (ticker != null) {
			ticker.cancel();
		}
		for (UUID playerId : new ArrayList<>(live.keySet())) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null) {
				player.closeInventory();
			}
		}
		live.clear();
	}

	// ------------------------------------------------------------------
	// plumbing

	private void refreshLive() {
		for (Map.Entry<UUID, Live> entry : new ArrayList<>(live.entrySet())) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null || player.getOpenInventory().getTopInventory().getHolder() != entry.getValue().view()) {
				live.remove(entry.getKey());
				continue;
			}
			try {
				entry.getValue().render().run();
			} catch (RuntimeException exception) {
				live.remove(entry.getKey());
			}
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		Live current = live.get(event.getPlayer().getUniqueId());
		if (current != null && event.getInventory().getHolder() == current.view()) {
			live.remove(event.getPlayer().getUniqueId());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		live.remove(event.getPlayer().getUniqueId());
	}

	/** Render, track and show a screen; a live one keeps re-rendering while it stays open. */
	private void show(Player player, GuiView view, Runnable render, boolean keepFresh) {
		render.run();
		guis.track(view);
		view.open(player);
		if (keepFresh) {
			live.put(player.getUniqueId(), new Live(view, render));
		}
	}

	private static Component title(String... crumbs) {
		String[] all = new String[crumbs.length + 1];
		all[0] = "DeathSwap";
		System.arraycopy(crumbs, 0, all, 1, crumbs.length);
		return LunaUi.guiTitleBreadcrumb(all);
	}

	private static ItemStack item(Material material, String name, List<String> lore) {
		List<Component> lines = new ArrayList<>();
		for (String line : lore) {
			lines.add(LunaUi.mini(line));
		}
		return LunaUi.item(material, name, lines);
	}

	private static ItemStack glint(ItemStack item, boolean on) {
		if (on) {
			ItemMeta meta = item.getItemMeta();
			meta.setEnchantmentGlintOverride(true);
			item.setItemMeta(meta);
		}
		return item;
	}

	private static ItemStack pane(Material material) {
		return item(material, " ", List.of());
	}

	private static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
		ItemStack head = item(Material.PLAYER_HEAD, name, lore);
		if (head.getItemMeta() instanceof SkullMeta skull) {
			skull.setOwningPlayer(owner);
			head.setItemMeta(skull);
		}
		return head;
	}

	/** Always pass a handler, so a re-render never leaves an old one on a slot. */
	private static void put(GuiView view, int slot, ItemStack item, GuiClickHandler handler) {
		view.setItem(slot, item, handler == null ? NOOP : handler);
	}

	private static void clear(GuiView view, int size) {
		for (int slot = 0; slot < size; slot++) {
			put(view, slot, null, null);
		}
	}

	private static void frame(GuiView view, int size, Material material) {
		ItemStack pane = pane(material);
		int rows = size / 9;
		for (int slot = 0; slot < size; slot++) {
			int row = slot / 9;
			int col = slot % 9;
			if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
				put(view, slot, pane, null);
			}
		}
	}

	private static void bottomBar(GuiView view) {
		ItemStack pane = pane(Material.BLACK_STAINED_GLASS_PANE);
		for (int slot = 45; slot < 54; slot++) {
			put(view, slot, pane, null);
		}
	}

	private static void click(Player player) {
		player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
	}

	private static void fail(Player player, String reason) {
		DeathSwapText.send(player, DeathSwapText.ERROR + DeathSwapText.escape(reason));
		player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
	}

	private static String name(UUID playerId) {
		Player online = Bukkit.getPlayer(playerId);
		if (online != null) {
			return online.getName();
		}
		String name = Bukkit.getOfflinePlayer(playerId).getName();
		return name == null ? playerId.toString().substring(0, 8) : name;
	}

	private static Material stateMaterial(Room room) {
		return switch (room.state()) {
			case WAITING -> Material.LIME_CONCRETE;
			case STARTING -> Material.YELLOW_CONCRETE;
			case PREPARING -> Material.ORANGE_CONCRETE;
			case PLAYING -> Material.RED_CONCRETE;
			case FINISHED -> Material.PURPLE_CONCRETE;
		};
	}

	private static String roomName(Room room) {
		return "<gradient:#7ED6FF:#A78BFA><b>⌂ " + DeathSwapText.escape(room.displayName()) + "</b></gradient>";
	}

	// ------------------------------------------------------------------
	// room list

	public void openBrowser(Player player, int page) {
		GuiView view = new GuiView(54, title("Danh sách phòng"));
		int[] currentPage = {Math.max(0, page)};
		Runnable[] render = new Runnable[1];
		render[0] = () -> renderBrowser(player, view, currentPage, render[0]);
		show(player, view, render[0], true);
	}

	private void renderBrowser(Player player, GuiView view, int[] page, Runnable self) {
		clear(view, 54);
		bottomBar(view);

		boolean admin = player.hasPermission("lunadeathswap.admin");
		Room mine = rooms.roomOf(player);
		List<Room> visible = new ArrayList<>();
		if (mine != null) {
			visible.add(mine);
		}
		for (Room room : rooms.rooms()) {
			if (room == mine) {
				continue;
			}
			if (room.settings().visibility == RoomSettings.Visibility.PUBLIC || admin || player.getUniqueId().equals(room.owner())) {
				visible.add(room);
			}
		}

		int pages = Math.max(1, (visible.size() + 44) / 45);
		page[0] = Math.min(page[0], pages - 1);
		int from = page[0] * 45;
		for (int index = from; index < Math.min(visible.size(), from + 45); index++) {
			Room room = visible.get(index);
			put(view, index - from, roomItem(player, room), (clicker, event, v) -> {
				click(clicker);
				browserClick(clicker, room, event.getClick());
			});
		}
		if (visible.isEmpty()) {
			put(view, 22, item(Material.PAPER, "<yellow>ℹ</yellow> Chưa có phòng nào",
				List.of("<gray>Hãy tạo phòng đầu tiên!</gray>")), null);
		}

		if (player.hasPermission("lunadeathswap.room.create") || admin) {
			put(view, 45, item(Material.LIME_WOOL, "<green>✔</green> Tạo phòng",
				List.of("<gray>Tạo phòng mới và làm chủ phòng.</gray>")), (clicker, event, v) -> {
					click(clicker);
					promptCreate(clicker);
				});
		}
		if (page[0] > 0) {
			put(view, 47, item(Material.ARROW, "<yellow>ℹ</yellow> Trang trước",
				List.of("<gray>Xem trang trước.</gray>")), (clicker, event, v) -> {
					page[0]--;
					self.run();
				});
		}
		put(view, 48, profileButton(player), (clicker, event, v) -> openProfile(clicker, clicker.getUniqueId()));
		put(view, 49, item(Material.CLOCK, "<yellow>ℹ</yellow> Làm mới",
			List.of("<gray>Cập nhật danh sách phòng.</gray>", "<gray>Trang " + (page[0] + 1) + "/" + pages + "</gray>")),
			(clicker, event, v) -> {
				click(clicker);
				self.run();
			});
		if (mine != null) {
			put(view, 50, item(Material.COMPASS, "<yellow>ℹ</yellow> Phòng của bạn",
				List.of("<gray>Mở phòng</gray> <white>" + DeathSwapText.escape(mine.displayName()) + "</white><gray>.</gray>")),
				(clicker, event, v) -> openRoom(clicker, mine.id()));
		}
		if (page[0] < pages - 1) {
			put(view, 51, item(Material.ARROW, "<yellow>ℹ</yellow> Trang sau",
				List.of("<gray>Xem trang tiếp theo.</gray>")), (clicker, event, v) -> {
					page[0]++;
					self.run();
				});
		}
		put(view, 53, item(Material.BARRIER, "<red>❌</red> Đóng", List.of("<gray>Đóng bảng chọn.</gray>")),
			(clicker, event, v) -> clicker.closeInventory());
	}

	private ItemStack roomItem(Player viewer, Room room) {
		RoomSettings s = room.settings();
		boolean member = room.isMember(viewer.getUniqueId());
		List<String> lore = new ArrayList<>();
		lore.add("<gray>ID: <white>" + DeathSwapText.escape(room.id()) + "</white></gray>");
		lore.add("<gray>Trạng thái: </gray>" + room.stateDisplay());
		lore.add("<gray>Người chơi: <white>" + room.members().size() + "/" + s.maxPlayers + "</white></gray>");
		lore.add("<gray>Chủ phòng: <white>" + DeathSwapText.escape(room.owner() == null ? "không có" : name(room.owner())) + "</white></gray>");
		lore.add("<gray>Hoán đổi: <white>" + DeathSwapText.clock(s.swapMin) + "–" + DeathSwapText.clock(s.swapMax) + "</white></gray>");
		lore.add("<gray>Độ khó: <white>" + choiceLabel("difficulty", s.difficulty.name().toLowerCase(Locale.ROOT)) + "</white>"
			+ " <dark_gray>●</dark_gray> PvP: " + (s.pvp ? "<green>bật</green>" : "<red>tắt</red>") + "</gray>");
		if (room.state() == Room.State.PLAYING) {
			lore.add("<gray>Còn sống: <white>" + room.aliveCount() + "/" + room.participantCount() + "</white>"
				+ " <dark_gray>●</dark_gray> ⌚ <white>" + DeathSwapText.clock(room.elapsedSeconds()) + "</white></gray>");
		}
		if (s.visibility == RoomSettings.Visibility.PRIVATE) {
			lore.add("<gray>🔒 Riêng tư</gray>");
		}
		lore.add(" ");
		if (member) {
			lore.add("<green>✔</green> <gray>Bạn đang ở phòng này.</gray>");
			lore.add("<yellow>▶</yellow> <white>Bấm để mở phòng</white>");
		} else if (room.isFull()) {
			lore.add("<red>❌</red> <gray>Phòng đã đầy.</gray>");
		} else {
			lore.add("<yellow>▶</yellow> <white>Chuột trái: vào phòng</white>");
		}
		if (room.state() == Room.State.PLAYING && !room.isAlive(viewer.getUniqueId())) {
			lore.add("<yellow>▶</yellow> <white>Chuột phải: xem ván</white>");
		}
		return glint(item(stateMaterial(room), roomName(room), lore), member);
	}

	private void browserClick(Player player, Room room, ClickType click) {
		if (click.isRightClick() && room.state() == Room.State.PLAYING && !room.isAlive(player.getUniqueId())) {
			openSpectate(player, room.id());
			return;
		}
		if (room.isMember(player.getUniqueId())) {
			openRoom(player, room.id());
			return;
		}
		join(player, room);
	}

	private void join(Player player, Room room) {
		if (room.settings().visibility == RoomSettings.Visibility.PRIVATE && !player.hasPermission("lunadeathswap.admin")
			&& !player.getUniqueId().equals(room.owner())) {
			fail(player, "Phòng này riêng tư; nhờ chủ phòng thêm bạn vào.");
			return;
		}
		Room current = rooms.roomOf(player);
		if (current != null && current.isAlive(player.getUniqueId())) {
			fail(player, "Bạn đang chơi ở phòng khác.");
			return;
		}
		switch (rooms.join(player, room)) {
			case JOINED, ALREADY -> {
				DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đã vào phòng " + room.tag() + ".");
				openRoom(player, room.id());
			}
			case FULL -> fail(player, "Phòng đã đầy.");
		}
	}

	private void promptCreate(Player player) {
		player.closeInventory();
		String suggestion = player.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
		anvil.open(player, AnvilInputManager.request(
			LunaUi.guiTitle("ID phòng mới"),
			(who, text) -> {
				String id = text.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
				Room current = rooms.roomOf(who);
				if (current != null && current.isAlive(who.getUniqueId())) {
					fail(who, "Bạn đang chơi, không thể tạo phòng lúc này.");
					return;
				}
				String error = rooms.create(id, who.getUniqueId(), "", false);
				if (error != null) {
					fail(who, error);
					openBrowser(who, 0);
					return;
				}
				Room room = rooms.get(id);
				rooms.join(who, room);
				DeathSwapText.send(who, DeathSwapText.SUCCESS + "Đã tạo phòng " + room.tag() + ". Bạn là chủ phòng.");
				openRoom(who, id);
			},
			who -> openBrowser(who, 0)
		).withInitialText(suggestion.isEmpty() ? "phong" : suggestion.substring(0, Math.min(16, suggestion.length())))
			.withInputMaterial(Material.NAME_TAG));
	}

	// ------------------------------------------------------------------
	// room panel

	public void openRoom(Player player, String roomId) {
		Room room = rooms.get(roomId);
		if (room == null) {
			fail(player, "Phòng không còn tồn tại.");
			openBrowser(player, 0);
			return;
		}
		GuiView view = new GuiView(54, title("Phòng", room.displayName()));
		Runnable[] render = new Runnable[1];
		render[0] = () -> renderRoom(player, view, roomId);
		show(player, view, render[0], true);
	}

	private void renderRoom(Player player, GuiView view, String roomId) {
		Room room = rooms.get(roomId);
		if (room == null) {
			player.closeInventory();
			fail(player, "Phòng đã bị đóng.");
			return;
		}
		boolean manager = rooms.canManage(player, room);
		UUID me = player.getUniqueId();
		clear(view, 54);
		frame(view, 54, Material.BLACK_STAINED_GLASS_PANE);

		put(view, 4, glint(item(stateMaterial(room), roomName(room), roomInfoLore(room)), true), null);

		List<UUID> members = new ArrayList<>(room.members());
		members.sort((a, b) -> Boolean.compare(!a.equals(room.owner()), !b.equals(room.owner())));
		for (int index = 0; index < HEAD_SLOTS.length; index++) {
			if (index >= members.size()) {
				break;
			}
			UUID memberId = members.get(index);
			List<String> lore = new ArrayList<>();
			lore.add(memberStatus(room, memberId));
			if (memberId.equals(room.owner())) {
				lore.add("<#ffd166>👑 Chủ phòng</#ffd166>");
			}
			if (manager && !memberId.equals(me)) {
				lore.add(" ");
				lore.add("<yellow>▶</yellow> <white>Bấm để quản lý</white>");
			}
			put(view, HEAD_SLOTS[index], head(Bukkit.getOfflinePlayer(memberId), "<white>" + DeathSwapText.escape(name(memberId)) + "</white>", lore),
				manager && !memberId.equals(me) ? (clicker, event, v) -> openMember(clicker, roomId, memberId) : null);
		}
		if (members.size() > HEAD_SLOTS.length) {
			put(view, 35, item(Material.PAPER, "<yellow>ℹ</yellow> Còn " + (members.size() - HEAD_SLOTS.length) + " người nữa",
				List.of("<gray>Xem đủ bằng /ds room info.</gray>")), null);
		}

		ItemStack gray = pane(Material.GRAY_STAINED_GLASS_PANE);
		for (int slot = 37; slot <= 43; slot++) {
			put(view, slot, gray, null);
		}
		if (manager) {
			renderManagerRow(player, view, room);
		}

		boolean member = room.isMember(me);
		put(view, 45, item(Material.ARROW, "<yellow>ℹ</yellow> Danh sách phòng", List.of("<gray>Quay về danh sách.</gray>")),
			(clicker, event, v) -> openBrowser(clicker, 0));
		if (member) {
			boolean sitting = room.isSittingOut(me);
			put(view, 47, item(sitting ? Material.GRAY_DYE : Material.LIME_DYE,
				sitting ? "<gray>👻 Đang ngồi ngoài</gray>" : "<green>👤 Sẵn sàng</green>",
				List.of(sitting ? "<gray>Bạn sẽ không vào ván sau.</gray>" : "<gray>Bạn sẽ vào ván tiếp theo.</gray>",
					" ", "<yellow>▶</yellow> <white>Bấm để " + (sitting ? "sẵn sàng" : "ngồi ngoài") + "</white>")),
				(clicker, event, v) -> {
					click(clicker);
					room.setSitOut(me, !sitting);
					rooms.sendToLobbyIfThere(clicker);
					rooms.refreshDisplay(clicker);
					renderRoom(clicker, view, roomId);
				});
		} else {
			put(view, 47, item(Material.LIME_WOOL, "<green>✔</green> Vào phòng", List.of("<gray>Tham gia phòng này.</gray>")),
				(clicker, event, v) -> join(clicker, room));
		}
		if (room.state() == Room.State.PLAYING && !room.isAlive(me)) {
			put(view, 48, item(Material.ENDER_EYE, "<aqua>👻 Xem ván</aqua>", List.of("<gray>Chọn người để theo dõi.</gray>")),
				(clicker, event, v) -> openSpectate(clicker, roomId));
		}
		put(view, 49, profileButton(player), (clicker, event, v) -> openProfile(clicker, clicker.getUniqueId()));
		if (member) {
			put(view, 51, item(Material.BARRIER, "<red>❌</red> Rời phòng",
				List.of(room.isAlive(me) ? "<gray>Bạn sẽ bỏ cuộc ván này.</gray>" : "<gray>Ra khỏi phòng hiện tại.</gray>")),
				(clicker, event, v) -> confirmLeaveRoom(clicker));
		}
		put(view, 53, item(Material.BARRIER, "<red>❌</red> Đóng", List.of("<gray>Đóng bảng phòng.</gray>")),
			(clicker, event, v) -> clicker.closeInventory());
	}

	private List<String> roomInfoLore(Room room) {
		RoomSettings s = room.settings();
		List<String> lore = new ArrayList<>();
		lore.add("<gray>ID: <white>" + DeathSwapText.escape(room.id()) + "</white>"
			+ (s.visibility == RoomSettings.Visibility.PRIVATE ? " <dark_gray>●</dark_gray> 🔒 riêng tư" : "") + "</gray>");
		lore.add("<gray>Trạng thái: </gray>" + room.stateDisplay() + " <gray>● Ván đã chơi: <white>" + room.rounds() + "</white></gray>");
		lore.add("<gray>Người chơi: <white>" + room.members().size() + "/" + s.maxPlayers + "</white>"
			+ " <dark_gray>●</dark_gray> cần <white>" + s.minPlayers + "</white></gray>");
		lore.add(" ");
		lore.add("<gray>Hoán đổi: <white>" + DeathSwapText.clock(s.swapMin) + "–" + DeathSwapText.clock(s.swapMax) + "</white>"
			+ (s.showTimer ? " <dark_gray>●</dark_gray> hiện giờ" : "") + "</gray>");
		lore.add("<gray>Độ khó: <white>" + choiceLabel("difficulty", s.difficulty.name().toLowerCase(Locale.ROOT)) + "</white>"
			+ " <dark_gray>●</dark_gray> PvP: " + (s.pvp ? "<green>bật</green>" : "<red>tắt</red>") + "</gray>");
		lore.add("<gray>Viền: <white>" + s.worldBorder + "</white> <dark_gray>●</dark_gray> Seed: <white>"
			+ (s.seed == 0 ? "ngẫu nhiên" : String.valueOf(s.seed)) + "</white></gray>");
		switch (room.state()) {
			case STARTING -> {
				lore.add(" ");
				lore.add("<yellow>⌛</yellow> <white>Bắt đầu sau " + room.countdownLeft() + "s</white>");
			}
			case PREPARING -> {
				lore.add(" ");
				lore.add("<yellow>⌛</yellow> <white>" + DeathSwapText.escape(room.preparingStage()) + "...</white>");
			}
			case PLAYING, FINISHED -> {
				lore.add(" ");
				lore.add("<gray>❤ Còn sống: <white>" + room.aliveCount() + "/" + room.participantCount() + "</white>"
					+ " <dark_gray>●</dark_gray> ↔ <white>#" + room.swaps() + "</white></gray>");
				lore.add("<gray>⌚ <white>" + DeathSwapText.clock(room.elapsedSeconds()) + "</white>"
					+ " <dark_gray>●</dark_gray> lần tới: <white>" + room.swapText() + "</white></gray>");
				if (room.state() == Room.State.FINISHED && room.winnerName() != null) {
					lore.add("<#ffd166>👑 " + DeathSwapText.escape(room.winnerName()) + " chiến thắng</#ffd166>");
				}
			}
			default -> {
			}
		}
		return lore;
	}

	private static String memberStatus(Room room, UUID memberId) {
		if (room.isAlive(memberId)) {
			return "<green>❤ Còn sống</green>";
		}
		if (room.isParticipant(memberId) && (room.state() == Room.State.PREPARING || room.state() == Room.State.STARTING)) {
			return "<yellow>⌛ Sắp vào trận</yellow>";
		}
		if (room.isParticipant(memberId) && room.inRound()) {
			return "<gray>☠ Đã bị loại</gray>";
		}
		if (room.isSittingOut(memberId)) {
			return "<gray>👻 Ngồi ngoài</gray>";
		}
		if (Bukkit.getPlayer(memberId) == null) {
			return "<dark_gray>Ngoại tuyến</dark_gray>";
		}
		return room.inRound() ? "<yellow>⌛ Chờ ván sau</yellow>" : "<green>👤 Sẵn sàng</green>";
	}

	private void renderManagerRow(Player player, GuiView view, Room room) {
		String roomId = room.id();
		put(view, 37, item(Material.COMPARATOR, "<aqua>🔧 Thiết lập phòng</aqua>",
			List.of("<gray>Chỉnh luật chơi và thế giới.</gray>")), (clicker, event, v) -> openSettings(clicker, roomId, 0));

		boolean idle = room.state() == Room.State.WAITING || room.state() == Room.State.STARTING;
		put(view, 38, item(idle ? Material.LIME_WOOL : Material.GRAY_WOOL,
			idle ? "<green>▶ Bắt đầu ván</green>" : "<gray>▶ Bắt đầu ván</gray>",
			idle ? List.of("<gray>Chuột trái: đếm ngược 10 giây.</gray>", "<gray>Chuột phải: chọn số giây.</gray>")
				: List.of("<gray>Phòng đang có ván.</gray>")),
			idle ? (clicker, event, v) -> {
				if (event.getClick().isRightClick()) {
					clicker.closeInventory();
					numbers.open(clicker, NumberSelectorGui.request(LunaUi.guiTitle("Đếm ngược"), "Số giây",
						(who, value) -> {
							startRound(who, roomId, (int) Math.round(value));
							openRoom(who, roomId);
						},
						who -> openRoom(who, roomId))
						.withRange(3, 600).withIntegerMode(true).withInitialValue(10).withUnit("giây")
						.withDisplayMaterial(Material.CLOCK));
				} else {
					click(clicker);
					startRound(clicker, roomId, 10);
				}
			} : null);

		boolean running = room.state() != Room.State.WAITING;
		put(view, 39, item(running ? Material.RED_WOOL : Material.GRAY_WOOL,
			running ? "<red>■ Dừng ván</red>" : "<gray>■ Dừng ván</gray>",
			List.of(running ? "<gray>Kết thúc ván, về sảnh.</gray>" : "<gray>Không có ván nào.</gray>")),
			running ? (clicker, event, v) -> confirm(clicker, "Dừng ván ở phòng " + room.displayName() + "?",
				List.of("<gray>Mọi người sẽ về sảnh.</gray>", "<gray>Ván này không tính kết quả.</gray>"),
				() -> {
					Room target = rooms.get(roomId);
					if (target != null) {
						target.stop("dừng bởi " + clicker.getName());
					}
					openRoom(clicker, roomId);
				},
				() -> openRoom(clicker, roomId)) : null);

		boolean playing = room.state() == Room.State.PLAYING;
		put(view, 40, item(playing ? Material.ENDER_PEARL : Material.GRAY_DYE,
			playing ? "<#36a8ff>↔ Hoán đổi ngay</#36a8ff>" : "<gray>↔ Hoán đổi ngay</gray>",
			List.of(playing ? "<gray>Đổi vị trí mọi người lập tức.</gray>" : "<gray>Chỉ dùng khi đang chơi.</gray>")),
			playing ? (clicker, event, v) -> {
				click(clicker);
				room.forceSwap();
			} : null);

		boolean isPrivate = room.settings().visibility == RoomSettings.Visibility.PRIVATE;
		put(view, 41, glint(item(isPrivate ? Material.IRON_DOOR : Material.OAK_DOOR,
			isPrivate ? "<red>🔒 Riêng tư</red>" : "<green>⌂ Công khai</green>",
			List.of(isPrivate ? "<gray>Chỉ người được thêm mới vào.</gray>" : "<gray>Ai cũng có thể vào.</gray>",
				" ", "<yellow>▶</yellow> <white>Bấm để đổi</white>")), isPrivate),
			(clicker, event, v) -> {
				click(clicker);
				String error = rooms.changeSetting(clicker, room, "visibility", isPrivate ? "public" : "private");
				if (error != null) {
					fail(clicker, error);
				}
				renderRoom(clicker, view, roomId);
			});

		put(view, 42, item(Material.PLAYER_HEAD, "<green>👤 Mời người chơi</green>",
			List.of("<gray>Thêm người đang online vào phòng.</gray>")), (clicker, event, v) -> openInvite(clicker, roomId, 0));

		put(view, 43, item(Material.TNT, "<red>❌ Đóng phòng</red>",
			List.of("<gray>Đóng phòng, mọi người về sảnh.</gray>")),
			(clicker, event, v) -> confirm(clicker, "Đóng phòng " + room.displayName() + "?",
				List.of("<gray>Ván đang chơi sẽ dừng.</gray>", "<gray>Thành viên về sảnh chung.</gray>"),
				() -> {
					rooms.delete(roomId, "đóng bởi " + clicker.getName());
					openBrowser(clicker, 0);
				},
				() -> openRoom(clicker, roomId)));
	}

	private void startRound(Player player, String roomId, int seconds) {
		Room room = rooms.get(roomId);
		if (room == null || !room.forceStart(seconds)) {
			fail(player, "Không thể bắt đầu ván lúc này.");
		}
	}

	public void confirmLeaveRoom(Player player) {
		Room room = rooms.roomOf(player);
		if (room == null) {
			fail(player, "Bạn không ở trong phòng nào.");
			return;
		}
		boolean alive = room.state() == Room.State.PLAYING && room.isAlive(player.getUniqueId());
		confirm(player, "Rời phòng " + room.displayName() + "?",
			alive ? List.of("<gray>Bạn đang chơi:</gray>", "<red>rời phòng là bỏ cuộc.</red>")
				: List.of("<gray>Bạn có thể vào lại sau.</gray>"),
			() -> {
				Room current = rooms.roomOf(player);
				if (current == null) {
					return;
				}
				if (current.state() == Room.State.PLAYING && current.isAlive(player.getUniqueId())) {
					current.returnToLobby(player);
				}
				rooms.leave(player, "đã rời phòng.");
				DeathSwapText.send(player, DeathSwapText.SUCCESS + "Đã rời phòng " + current.tag() + ".");
			},
			null);
	}

	// ------------------------------------------------------------------
	// member actions and invites

	private void openMember(Player player, String roomId, UUID targetId) {
		Room room = rooms.get(roomId);
		if (room == null || !rooms.canManage(player, room) || !room.isMember(targetId)) {
			openRoom(player, roomId);
			return;
		}
		GuiView view = new GuiView(27, title(room.displayName(), name(targetId)));
		show(player, view, () -> {
			clear(view, 27);
			ItemStack gray = pane(Material.GRAY_STAINED_GLASS_PANE);
			for (int slot = 0; slot < 27; slot++) {
				put(view, slot, gray, null);
			}
			put(view, 4, head(Bukkit.getOfflinePlayer(targetId), "<white>" + DeathSwapText.escape(name(targetId)) + "</white>",
				List.of(memberStatus(room, targetId))), null);

			Player target = Bukkit.getPlayer(targetId);
			put(view, 11, item(Material.RED_WOOL, "<red>❌</red> Mời ra khỏi phòng",
				List.of("<gray>Người này sẽ về sảnh chung.</gray>")),
				target == null ? null : (clicker, event, v) -> confirm(clicker, "Mời " + target.getName() + " ra?",
					List.of("<gray>Nếu đang chơi sẽ bị loại.</gray>"),
					() -> {
						if (target.isOnline() && rooms.roomOf(target) == room) {
							rooms.leave(target, "đã bị mời ra khỏi phòng.");
							DeathSwapText.send(target, DeathSwapText.WARN + "Bạn đã bị mời ra khỏi phòng " + room.tag() + ".");
						}
						openRoom(clicker, roomId);
					},
					() -> openMember(clicker, roomId, targetId)));

			boolean sitting = room.isSittingOut(targetId);
			put(view, 13, item(sitting ? Material.LIME_DYE : Material.GRAY_DYE,
				sitting ? "<green>👤 Cho tham gia</green>" : "<gray>👻 Cho ngồi ngoài</gray>",
				List.of(sitting ? "<gray>Người này sẽ vào ván sau.</gray>" : "<gray>Người này sẽ không vào ván sau.</gray>")),
				(clicker, event, v) -> {
					click(clicker);
					room.setSitOut(targetId, !sitting);
					if (target != null) {
						rooms.sendToLobbyIfThere(target);
						rooms.refreshDisplay(target);
					}
					openMember(clicker, roomId, targetId);
				});

			boolean owner = targetId.equals(room.owner());
			put(view, 15, glint(item(Material.GOLDEN_HELMET, owner ? "<#ffd166>👑 Đang là chủ phòng</#ffd166>" : "<#ffd166>👑 Trao quyền chủ phòng</#ffd166>",
				List.of(owner ? "<gray>Người này đang quản lý phòng.</gray>" : "<gray>Người này sẽ quản lý phòng.</gray>")), owner),
				owner ? null : (clicker, event, v) -> confirm(clicker, "Trao phòng cho " + name(targetId) + "?",
					List.of("<gray>Bạn sẽ không còn là chủ.</gray>"),
					() -> {
						rooms.setOwner(room, targetId);
						room.broadcast(DeathSwapText.INFO + DeathSwapText.white(name(targetId)) + " giờ là chủ phòng.");
						openRoom(clicker, roomId);
					},
					() -> openMember(clicker, roomId, targetId)));

			put(view, 22, item(Material.ARROW, "<yellow>ℹ</yellow> Quay lại", List.of("<gray>Quay về phòng.</gray>")),
				(clicker, event, v) -> openRoom(clicker, roomId));
		}, false);
	}

	/** Pick a living player to follow in a running round (live: hearts and distance refresh). */
	public void openSpectate(Player player, String roomId) {
		Room room = rooms.get(roomId);
		if (room == null || room.state() != Room.State.PLAYING) {
			fail(player, "Không có ván nào đang diễn ra để xem.");
			return;
		}
		GuiView view = new GuiView(54, title(room.displayName(), "Theo dõi"));
		Runnable[] render = new Runnable[1];
		render[0] = () -> {
			Room current = rooms.get(roomId);
			if (current == null || current.state() != Room.State.PLAYING) {
				player.closeInventory();
				return;
			}
			clear(view, 54);
			bottomBar(view);
			List<Player> targets = current.spectatable();
			for (int index = 0; index < Math.min(45, targets.size()); index++) {
				Player target = targets.get(index);
				List<String> lore = new ArrayList<>();
				lore.add("<red>❤</red> <white>" + Math.round(target.getHealth()) + "</white><gray>/20</gray> <dark_gray>●</dark_gray> <gold>🍖</gold> <white>"
					+ target.getFoodLevel() + "</white>");
				lore.add("<gray>Tọa độ:</gray> <white>" + target.getLocation().getBlockX() + " " + target.getLocation().getBlockY() + " "
					+ target.getLocation().getBlockZ() + "</white>");
				int traps = current.roundTraps(target.getUniqueId());
				if (traps > 0) {
					lore.add("<gray>Bẫy ván này:</gray> <white>" + traps + "</white>");
				}
				lore.add(" ");
				lore.add("<yellow>▶</yellow> <white>Bấm để theo dõi</white>");
				put(view, index, head(target, "<white>" + DeathSwapText.escape(target.getName()) + "</white>", lore),
					(clicker, event, v) -> {
						clicker.closeInventory();
						String error = current.spectatePlayer(clicker, target);
						if (error != null) {
							fail(clicker, error);
						}
					});
			}
			if (targets.isEmpty()) {
				put(view, 22, item(Material.PAPER, "<yellow>ℹ</yellow> Không còn ai",
					List.of("<gray>Không còn người sống để xem.</gray>")), null);
			}
			if (!current.isAlive(player.getUniqueId())) {
				put(view, 47, item(Material.ENDER_EYE, "<aqua>👻 Bay tự do</aqua>",
					List.of("<gray>Xem ván ở chế độ bay.</gray>")),
					(clicker, event, v) -> {
						clicker.closeInventory();
						clicker.setSpectatorTarget(null);
						if (!(clicker.getGameMode() == org.bukkit.GameMode.SPECTATOR && current.world() != null
							&& clicker.getWorld().equals(current.world())) && !current.spectate(clicker)) {
							fail(clicker, "Chưa xem được ván này.");
						}
					});
			}
			put(view, 49, item(Material.CLOCK, "<yellow>ℹ</yellow> Làm mới", List.of("<gray>Cập nhật danh sách.</gray>")),
				(clicker, event, v) -> render[0].run());
			put(view, 51, item(Material.ARROW, "<yellow>ℹ</yellow> Về phòng", List.of("<gray>Mở bảng phòng.</gray>")),
				(clicker, event, v) -> openRoom(clicker, roomId));
			put(view, 53, item(Material.BARRIER, "<red>❌</red> Đóng", List.of("<gray>Đóng danh sách.</gray>")),
				(clicker, event, v) -> clicker.closeInventory());
		};
		show(player, view, render[0], true);
	}

	private void openInvite(Player player, String roomId, int page) {
		Room room = rooms.get(roomId);
		if (room == null || !rooms.canManage(player, room)) {
			openRoom(player, roomId);
			return;
		}
		GuiView view = new GuiView(54, title(room.displayName(), "Mời người chơi"));
		int[] currentPage = {Math.max(0, page)};
		Runnable[] render = new Runnable[1];
		render[0] = () -> {
			Room current = rooms.get(roomId);
			if (current == null) {
				player.closeInventory();
				return;
			}
			clear(view, 54);
			bottomBar(view);
			List<Player> candidates = new ArrayList<>();
			for (Player online : Bukkit.getOnlinePlayers()) {
				Room theirs = rooms.roomOf(online);
				if (theirs == current || (theirs != null && theirs.isAlive(online.getUniqueId()))) {
					continue;
				}
				candidates.add(online);
			}
			int pages = Math.max(1, (candidates.size() + 44) / 45);
			currentPage[0] = Math.min(currentPage[0], pages - 1);
			int from = currentPage[0] * 45;
			for (int index = from; index < Math.min(candidates.size(), from + 45); index++) {
				Player target = candidates.get(index);
				Room theirs = rooms.roomOf(target);
				put(view, index - from, head(target, "<white>" + DeathSwapText.escape(target.getName()) + "</white>", List.of(
					theirs == null ? "<gray>Chưa vào phòng nào.</gray>" : "<gray>Đang ở phòng</gray> <white>" + DeathSwapText.escape(theirs.displayName()) + "</white>",
					" ", "<yellow>▶</yellow> <white>Bấm để thêm vào phòng</white>")),
					(clicker, event, v) -> {
						click(clicker);
						invite(clicker, current, target);
						render[0].run();
					});
			}
			if (candidates.isEmpty()) {
				put(view, 22, item(Material.PAPER, "<yellow>ℹ</yellow> Không có người chơi",
					List.of("<gray>Không có ai để mời lúc này.</gray>")), null);
			} else {
				put(view, 45, item(Material.LIME_WOOL, "<green>✔</green> Thêm tất cả",
					List.of("<gray>Thêm " + candidates.size() + " người vào phòng.</gray>")),
					(clicker, event, v) -> {
						for (Player target : candidates) {
							invite(clicker, current, target);
						}
						render[0].run();
					});
			}
			if (currentPage[0] > 0) {
				put(view, 47, item(Material.ARROW, "<yellow>ℹ</yellow> Trang trước", List.of("<gray>Xem trang trước.</gray>")),
					(clicker, event, v) -> {
						currentPage[0]--;
						render[0].run();
					});
			}
			put(view, 49, item(Material.CLOCK, "<yellow>ℹ</yellow> Làm mới", List.of("<gray>Cập nhật danh sách.</gray>")),
				(clicker, event, v) -> render[0].run());
			if (currentPage[0] < pages - 1) {
				put(view, 51, item(Material.ARROW, "<yellow>ℹ</yellow> Trang sau", List.of("<gray>Xem trang tiếp theo.</gray>")),
					(clicker, event, v) -> {
						currentPage[0]++;
						render[0].run();
					});
			}
			put(view, 53, item(Material.ARROW, "<yellow>ℹ</yellow> Quay lại", List.of("<gray>Quay về phòng.</gray>")),
				(clicker, event, v) -> openRoom(clicker, roomId));
		};
		show(player, view, render[0], true);
	}

	private void invite(Player manager, Room room, Player target) {
		Room theirs = rooms.roomOf(target);
		if (theirs != null && theirs.isAlive(target.getUniqueId())) {
			fail(manager, target.getName() + " đang chơi ở phòng khác.");
			return;
		}
		switch (rooms.join(target, room)) {
			case JOINED -> DeathSwapText.send(target, DeathSwapText.INFO + DeathSwapText.white(manager.getName())
				+ " đã thêm bạn vào phòng " + room.tag() + ".");
			case FULL -> fail(manager, "Phòng đã đầy.");
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------
	// settings

	public void openSettings(Player player, String roomId, int page) {
		Room room = rooms.get(roomId);
		if (room == null) {
			fail(player, "Phòng không còn tồn tại.");
			return;
		}
		if (!rooms.canManage(player, room)) {
			fail(player, "Chỉ chủ phòng mới chỉnh được thiết lập.");
			openRoom(player, roomId);
			return;
		}
		List<String> keys = settingKeys(player);
		int pages = Math.max(1, (keys.size() + SETTING_ROWS_PER_PAGE - 1) / SETTING_ROWS_PER_PAGE);
		int[] currentPage = {Math.min(Math.max(0, page), pages - 1)};
		GuiView view = new GuiView(54, title(room.displayName(), "Thiết lập"));
		Runnable[] render = new Runnable[1];
		render[0] = () -> renderSettings(player, view, roomId, keys, currentPage, pages, render[0]);
		show(player, view, render[0], false);
	}

	private static List<String> settingKeys(Player player) {
		List<String> keys = new ArrayList<>(RoomSettings.keys());
		if (!player.hasPermission("lunadeathswap.admin")) {
			keys.remove("persistent");
		}
		return keys;
	}

	private void renderSettings(Player player, GuiView view, String roomId, List<String> keys, int[] page, int pages, Runnable self) {
		Room room = rooms.get(roomId);
		if (room == null) {
			player.closeInventory();
			return;
		}
		clear(view, 54);
		frame(view, 54, Material.BLACK_STAINED_GLASS_PANE);
		ItemStack spacer = pane(Material.GRAY_STAINED_GLASS_PANE);
		RoomSettings defaults = rooms.settings().roomDefaults();

		int from = page[0] * SETTING_ROWS_PER_PAGE;
		for (int row = 0; row < SETTING_ROWS_PER_PAGE && from + row < keys.size(); row++) {
			String key = keys.get(from + row);
			int base = ROW_BASES[row];
			RoomSettings.Option option = RoomSettings.OPTIONS.get(key);
			String current = room.settings().get(key);
			String fallback = defaults.get(key);

			List<String> lore = new ArrayList<>();
			lore.add("<gray>Hiện tại: " + valueText(key, current, fallback) + "</gray>");
			lore.add("<gray>Mặc định: " + valueText(key, fallback, fallback) + "</gray>");
			lore.add(" ");
			for (String line : LunaLore.wrapLoreLine("<gray>" + DeathSwapText.escape(option.description()) + "</gray>")) {
				lore.add(line);
			}
			lore.add(" ");
			lore.add(current.equals(fallback) ? "<dark_gray>Đang dùng mặc định.</dark_gray>" : "<yellow>▶</yellow> <white>Bấm để về mặc định</white>");
			put(view, base, item(ICONS.getOrDefault(key, Material.PAPER), "<yellow>ℹ</yellow> <white>" + LABELS.getOrDefault(key, key) + "</white>", lore),
				current.equals(fallback) ? null : (clicker, event, v) -> {
					apply(clicker, room, key, fallback);
					self.run();
				});
			put(view, base + 1, spacer, null);

			List<Option> options = optionsFor(player, room, key, option, current, page, self);
			for (int index = 0; index < options.size() && index < 5; index++) {
				Option entry = options.get(index);
				put(view, base + 2 + index, glint(item(entry.icon(), entry.name(), entry.lore()), entry.selected()), entry.handler());
			}
		}

		put(view, 45, page[0] > 0
			? item(Material.ARROW, "<yellow>ℹ</yellow> Trang trước", List.of("<gray>Xem trang trước.</gray>"))
			: pane(Material.BLACK_STAINED_GLASS_PANE),
			page[0] > 0 ? (clicker, event, v) -> {
				page[0]--;
				self.run();
			} : null);
		put(view, 47, item(Material.WATER_BUCKET, "<aqua>🔧 Đặt lại tất cả</aqua>",
			List.of("<gray>Về thiết lập mặc định.</gray>", "<gray>Giữ tên và hiển thị.</gray>")),
			(clicker, event, v) -> confirm(clicker, "Đặt lại thiết lập phòng?",
				List.of("<gray>Mọi luật chơi về mặc định.</gray>"),
				() -> {
					for (String key : RoomSettings.keys()) {
						if (List.of("name", "visibility", "persistent").contains(key)) {
							continue;
						}
						room.settings().set(key, defaults.get(key));
					}
					rooms.save();
					room.broadcast(DeathSwapText.INFO + "Thiết lập phòng đã về mặc định.");
					openSettings(clicker, roomId, page[0]);
				},
				() -> openSettings(clicker, roomId, page[0])));
		put(view, 48, item(Material.MAP, "<yellow>ℹ</yellow> Trang",
			List.of("<gray>Trang hiện tại: <white>" + (page[0] + 1) + "/" + pages + "</white></gray>")), null);
		put(view, 49, item(Material.ARROW, "<yellow>ℹ</yellow> Quay lại", List.of("<gray>Quay về phòng.</gray>")),
			(clicker, event, v) -> openRoom(clicker, roomId));
		put(view, 53, page[0] < pages - 1
			? item(Material.ARROW, "<yellow>ℹ</yellow> Trang sau", List.of("<gray>Xem trang tiếp theo.</gray>"))
			: pane(Material.BLACK_STAINED_GLASS_PANE),
			page[0] < pages - 1 ? (clicker, event, v) -> {
				page[0]++;
				self.run();
			} : null);
	}

	private record Option(Material icon, String name, List<String> lore, boolean selected, GuiClickHandler handler) {
	}

	private List<Option> optionsFor(Player player, Room room, String key, RoomSettings.Option option, String current, int[] page, Runnable self) {
		List<Option> out = new ArrayList<>();
		String roomId = room.id();
		switch (option.type()) {
			case "bool" -> {
				boolean on = Boolean.parseBoolean(current);
				out.add(new Option(Material.LIME_WOOL, "<green>✔</green> Bật", List.of("<gray>Bật thiết lập này.</gray>"), on,
					on ? null : (clicker, event, v) -> {
						apply(clicker, room, key, "true");
						self.run();
					}));
				out.add(new Option(Material.RED_WOOL, "<red>❌</red> Tắt", List.of("<gray>Tắt thiết lập này.</gray>"), !on,
					!on ? null : (clicker, event, v) -> {
						apply(clicker, room, key, "false");
						self.run();
					}));
			}
			case "choice" -> {
				for (String value : option.suggestions()) {
					boolean selected = value.equalsIgnoreCase(current);
					out.add(new Option(choiceIcon(key, value), choiceColor(key, value) + choiceLabel(key, value),
						List.of("<gray>" + choiceHint(key, value) + "</gray>"), selected,
						selected ? null : (clicker, event, v) -> {
							apply(clicker, room, key, value);
							self.run();
						}));
				}
			}
			case "int" -> {
				int min = Integer.parseInt(option.suggestions().get(0));
				int max = Integer.parseInt(option.suggestions().get(1));
				int value = Integer.parseInt(current);
				int[] steps = STEPS.getOrDefault(key, new int[] {10, 1});
				for (int delta : new int[] {-steps[0], -steps[1], steps[1], steps[0]}) {
					int target = Math.max(min, Math.min(max, value + delta));
					boolean possible = target != value;
					String amount = SECONDS.contains(key) ? Math.abs(delta) + " giây" : String.valueOf(Math.abs(delta));
					out.add(new Option(delta < 0 ? Material.RED_WOOL : Material.LIME_WOOL,
						(delta < 0 ? "<red>−</red> " : "<green>+</green> ") + "<white>" + amount + "</white>",
						List.of(possible ? "<gray>Thành " + valueText(key, String.valueOf(target), String.valueOf(target)) + "</gray>"
							: "<gray>Đã tới giới hạn.</gray>"),
						false,
						possible ? (clicker, event, v) -> {
							apply(clicker, room, key, String.valueOf(target));
							self.run();
						} : null));
				}
				out.add(new Option(Material.OAK_SIGN, "<aqua>✦ Nhập số</aqua>",
					List.of("<gray>Chọn chính xác từ " + min + " đến " + max + ".</gray>"), false,
					(clicker, event, v) -> {
						clicker.closeInventory();
						numbers.open(clicker, NumberSelectorGui.request(LunaUi.guiTitle(LABELS.getOrDefault(key, key)), LABELS.getOrDefault(key, key),
							(who, number) -> {
								apply(who, room, key, String.valueOf(Math.round(number)));
								openSettings(who, roomId, page[0]);
							},
							who -> openSettings(who, roomId, page[0]))
							.withRange(min, max).withIntegerMode(true).withInitialValue(value)
							.withUnit(SECONDS.contains(key) ? "giây" : "")
							.withDisplayMaterial(ICONS.getOrDefault(key, Material.PAPER)));
					}));
			}
			case "text" -> {
				out.add(new Option(Material.NAME_TAG, "<aqua>✦ Đổi tên</aqua>", List.of("<gray>Nhập tên hiển thị mới.</gray>"), false,
					(clicker, event, v) -> {
						clicker.closeInventory();
						anvil.open(clicker, AnvilInputManager.request(LunaUi.guiTitle("Tên phòng"),
							(who, text) -> {
								apply(who, room, key, text.trim());
								openSettings(who, roomId, page[0]);
							},
							who -> openSettings(who, roomId, page[0]))
							.withInitialText(current.isEmpty() ? room.id() : current)
							.withInputMaterial(Material.NAME_TAG));
					}));
				out.add(new Option(Material.PAPER, "<gray>Dùng ID làm tên</gray>", List.of("<gray>Xóa tên hiển thị riêng.</gray>"),
					current.isEmpty(), current.isEmpty() ? null : (clicker, event, v) -> {
						apply(clicker, room, key, "");
						self.run();
					}));
			}
			case "long" -> {
				boolean random = "0".equals(current);
				out.add(new Option(Material.ENDER_CHEST, "<green>✔</green> Ngẫu nhiên",
					List.of("<gray>Dùng thế giới chờ sẵn.</gray>", "<gray>Vào ván ngay lập tức.</gray>"), random,
					random ? null : (clicker, event, v) -> {
						apply(clicker, room, key, "0");
						self.run();
					}));
				out.add(new Option(Material.WHEAT_SEEDS, "<aqua>✦ Nhập seed</aqua>",
					List.of("<gray>Tạo đúng thế giới này.</gray>", "<gray>Mất khoảng 20 giây.</gray>"), !random,
					(clicker, event, v) -> {
						clicker.closeInventory();
						anvil.open(clicker, AnvilInputManager.request(LunaUi.guiTitle("Seed thế giới"),
							(who, text) -> {
								String trimmed = text.trim();
								try {
									Long.parseLong(trimmed);
									apply(who, room, key, trimmed);
								} catch (NumberFormatException exception) {
									fail(who, "Seed phải là một số nguyên.");
								}
								openSettings(who, roomId, page[0]);
							},
							who -> openSettings(who, roomId, page[0]))
							.withInitialText(random ? "" : current)
							.withInputMaterial(Material.WHEAT_SEEDS));
					}));
			}
			default -> {
			}
		}
		return out;
	}

	private void apply(Player player, Room room, String key, String value) {
		String error = rooms.changeSetting(player, room, key, value);
		if (error != null) {
			fail(player, error);
		} else {
			click(player);
		}
	}

	private static String valueText(String key, String value, String fallback) {
		String color = value.equals(fallback) ? "<white>" : "<#ffd166>";
		String close = value.equals(fallback) ? "</white>" : "</#ffd166>";
		String shown;
		RoomSettings.Option option = RoomSettings.OPTIONS.get(key);
		if (option == null) {
			shown = DeathSwapText.escape(value);
		} else {
			switch (option.type()) {
				case "bool" -> shown = Boolean.parseBoolean(value) ? "bật" : "tắt";
				case "choice" -> shown = choiceLabel(key, value);
				case "long" -> shown = "0".equals(value) ? "ngẫu nhiên" : DeathSwapText.escape(value);
				case "text" -> shown = value.isEmpty() ? "(theo ID)" : DeathSwapText.escape(value);
				default -> shown = SECONDS.contains(key) ? DeathSwapText.clock(Long.parseLong(value)) : DeathSwapText.escape(value);
			}
		}
		return color + shown + close;
	}

	private static String choiceLabel(String key, String value) {
		return switch (key + ":" + value.toLowerCase(Locale.ROOT)) {
			case "visibility:public" -> "Công khai";
			case "visibility:private" -> "Riêng tư";
			case "difficulty:peaceful" -> "Hòa bình";
			case "difficulty:easy" -> "Dễ";
			case "difficulty:normal" -> "Thường";
			case "difficulty:hard" -> "Khó";
			case "swap-pattern:shuffle" -> "Vòng mới mỗi lần";
			case "swap-pattern:random" -> "Ngẫu nhiên";
			case "swap-pattern:ring" -> "Vòng cố định";
			case "swap-pattern:pairs" -> "Theo cặp";
			case "kit:none" -> "Không có";
			case "kit:food" -> "Đồ ăn";
			case "kit:basic" -> "Xô nước + đồ ăn";
			case "kit:tools" -> "Đồ đá + xô nước + đồ ăn";
			default -> value;
		};
	}

	private static String choiceHint(String key, String value) {
		return switch (key + ":" + value.toLowerCase(Locale.ROOT)) {
			case "visibility:public" -> "Ai cũng thấy và vào được.";
			case "visibility:private" -> "Ẩn khỏi danh sách phòng.";
			case "difficulty:peaceful" -> "Không có quái vật.";
			case "difficulty:easy" -> "Quái yếu, đói chậm.";
			case "difficulty:normal" -> "Như sinh tồn thường.";
			case "difficulty:hard" -> "Quái mạnh, nguy hiểm hơn.";
			case "swap-pattern:shuffle" -> "Mỗi lần xếp lại một vòng ngẫu nhiên.";
			case "swap-pattern:random" -> "Ai cũng có thể đổi với ai, kể cả từng cặp.";
			case "swap-pattern:ring" -> "Một vòng cố định từ đầu trận.";
			case "swap-pattern:pairs" -> "Cặp đôi cố định, đổi qua lại với nhau.";
			case "kit:none" -> "Tay trắng.";
			case "kit:food" -> "16 bánh mì.";
			case "kit:basic" -> "Xô nước, 16 bánh mì.";
			case "kit:tools" -> "Cuốc, rìu, xẻng đá, xô nước, 16 bánh mì.";
			default -> "";
		};
	}

	private static String choiceColor(String key, String value) {
		return switch (key + ":" + value.toLowerCase(Locale.ROOT)) {
			case "visibility:public", "difficulty:easy" -> "<green>";
			case "visibility:private", "difficulty:hard" -> "<red>";
			case "difficulty:peaceful" -> "<aqua>";
			default -> "<yellow>";
		};
	}

	private static Material choiceIcon(String key, String value) {
		return switch (key + ":" + value.toLowerCase(Locale.ROOT)) {
			case "visibility:public", "difficulty:easy" -> Material.LIME_WOOL;
			case "visibility:private", "difficulty:hard" -> Material.RED_WOOL;
			case "difficulty:peaceful" -> Material.LIGHT_BLUE_WOOL;
			default -> Material.YELLOW_WOOL;
		};
	}

	// ------------------------------------------------------------------
	// profile and leaderboard

	private ItemStack profileButton(Player player) {
		return head(player, "<yellow>Hồ sơ của</yellow> <white>" + DeathSwapText.escape(player.getName()) + "</white>",
			List.of("<gray>Xem thành tích DeathSwap.</gray>"));
	}

	public void openProfile(Player viewer, UUID targetId) {
		OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
		String targetName = name(targetId);
		GuiView view = new GuiView(27, title("Hồ sơ", targetName));
		show(viewer, view, () -> {
			clear(view, 27);
			ItemStack gray = pane(Material.GRAY_STAINED_GLASS_PANE);
			for (int slot = 0; slot < 27; slot++) {
				put(view, slot, gray, null);
			}
			DeathSwapStats.Entry entry = stats.get(targetId);
			int rank = stats.rankOf(targetId);
			double rate = entry.games == 0 ? 0D : entry.wins * 100D / entry.games;
			Room room = rooms.roomOf(targetId);

			put(view, 10, head(target, "<white><b>" + DeathSwapText.escape(targetName) + "</b></white>", List.of(
				"<gray>Hạng: " + (rank == 0 ? "<white>chưa xếp hạng</white>" : "<#ffd166>#" + rank + "</#ffd166>") + "</gray>",
				"<gray>Phòng: <white>" + (room == null ? "chưa vào" : DeathSwapText.escape(room.displayName())) + "</white></gray>",
				"<gray>Trạng thái: </gray>" + (Bukkit.getPlayer(targetId) != null ? "<green>online</green>" : "<dark_gray>offline</dark_gray>"))), null);
			put(view, 12, item(Material.IRON_SWORD, "<#36a8ff>⚔ Đã chơi</#36a8ff>", List.of(
				"<white><b>" + entry.games + "</b></white> <gray>ván</gray>")), null);
			put(view, 13, glint(item(Material.GOLDEN_HELMET, "<#ffd166>👑 Chiến thắng</#ffd166>", List.of(
				"<white><b>" + entry.wins + "</b></white> <gray>ván</gray>",
				"<gray>Tỉ lệ thắng: <white>" + String.format(Locale.ROOT, "%.1f", rate) + "%</white></gray>")), entry.wins > 0), null);
			put(view, 14, item(Material.TRIPWIRE_HOOK, "<#ffc012>🔥 Bẫy thành công</#ffc012>", List.of(
				"<white><b>" + entry.traps + "</b></white> <gray>lần</gray>",
				"<gray>Người khác chết ở chỗ bạn.</gray>")), null);
			put(view, 15, item(Material.CLOCK, "<#32faa7>⌚ Sống lâu nhất</#32faa7>", List.of(
				"<white><b>" + DeathSwapText.clock(entry.longestSurviveSeconds) + "</b></white>")), null);
			put(view, 16, item(Material.SKELETON_SKULL, "<red>☠ Bị loại</red>", List.of(
				"<white><b>" + entry.deaths + "</b></white> <gray>lần</gray>")), null);

			put(view, 21, item(Material.GOLD_BLOCK, "<#ffd166>★ Bảng xếp hạng</#ffd166>",
				List.of("<gray>Những người chơi giỏi nhất.</gray>")), (clicker, event, v) -> openLeaderboard(clicker));
			put(view, 22, item(Material.BARRIER, "<red>❌</red> Đóng", List.of("<gray>Đóng hồ sơ.</gray>")),
				(clicker, event, v) -> clicker.closeInventory());
			put(view, 23, item(Material.NETHER_STAR, "<gold>⌂ Danh sách phòng</gold>",
				List.of("<gray>Xem và chọn phòng.</gray>")), (clicker, event, v) -> openBrowser(clicker, 0));
		}, false);
	}

	public void openLeaderboard(Player player) {
		GuiView view = new GuiView(54, title("Bảng xếp hạng"));
		show(player, view, () -> {
			clear(view, 54);
			bottomBar(view);
			List<Map.Entry<UUID, DeathSwapStats.Entry>> top = stats.top(45);
			for (int index = 0; index < top.size(); index++) {
				UUID playerId = top.get(index).getKey();
				DeathSwapStats.Entry entry = top.get(index).getValue();
				String medal = switch (index) {
					case 0 -> "<#ffd166>#1</#ffd166>";
					case 1 -> "<#d1d5db>#2</#d1d5db>";
					case 2 -> "<#d97706>#3</#d97706>";
					default -> "<gray>#" + (index + 1) + "</gray>";
				};
				String display = entry.name == null || entry.name.isBlank() ? name(playerId) : entry.name;
				ItemStack head = head(Bukkit.getOfflinePlayer(playerId), medal + " <white>" + DeathSwapText.escape(display) + "</white>", List.of(
					"<gray>👑 <white>" + entry.wins + "</white> thắng <dark_gray>●</dark_gray> ⚔ <white>" + entry.games + "</white> ván</gray>",
					"<gray>🔥 <white>" + entry.traps + "</white> bẫy <dark_gray>●</dark_gray> ⌚ <white>" + DeathSwapText.clock(entry.longestSurviveSeconds) + "</white></gray>",
					" ", "<yellow>▶</yellow> <white>Bấm để xem hồ sơ</white>"));
				put(view, index, glint(head, index < 3), (clicker, event, v) -> openProfile(clicker, playerId));
			}
			if (top.isEmpty()) {
				put(view, 22, item(Material.PAPER, "<yellow>ℹ</yellow> Chưa có ai",
					List.of("<gray>⚔ Chơi một ván để lên bảng.</gray>")), null);
			}
			put(view, 49, item(Material.ARROW, "<yellow>ℹ</yellow> Quay lại", List.of("<gray>Về hồ sơ của bạn.</gray>")),
				(clicker, event, v) -> openProfile(clicker, clicker.getUniqueId()));
			put(view, 53, item(Material.BARRIER, "<red>❌</red> Đóng", List.of("<gray>Đóng bảng xếp hạng.</gray>")),
				(clicker, event, v) -> clicker.closeInventory());
		}, false);
	}

	// ------------------------------------------------------------------
	// confirm

	/** Yes/no in manhunt's layout: confirm at 11, note at 13, cancel at 15. A null onNo just closes. */
	public void confirm(Player player, String question, List<String> notes, Runnable onYes, Runnable onNo) {
		GuiView view = new GuiView(27, LunaUi.guiTitle("<color:#b91c1c>" + DeathSwapText.escape(question) + "</color>"));
		show(player, view, () -> {
			ItemStack gray = pane(Material.GRAY_STAINED_GLASS_PANE);
			for (int slot = 0; slot < 27; slot++) {
				put(view, slot, gray, null);
			}
			put(view, 11, item(Material.LIME_WOOL, "<green>✔</green> Xác nhận", List.of("<gray>Thực hiện ngay.</gray>")),
				(clicker, event, v) -> {
					clicker.closeInventory();
					click(clicker);
					onYes.run();
				});
			List<String> lore = new ArrayList<>(notes);
			put(view, 13, item(Material.PAPER, "<yellow>ℹ</yellow> " + DeathSwapText.escape(question), lore), null);
			put(view, 15, item(Material.RED_WOOL, "<red>❌</red> Hủy", List.of("<gray>Quay lại, không làm gì.</gray>")),
				(clicker, event, v) -> {
					clicker.closeInventory();
					if (onNo != null) {
						onNo.run();
					}
				});
		}, false);
	}

	/** /spawn while alive: forfeit only after a yes. */
	public void confirmForfeit(Player player) {
		confirm(player, "Rời ván và về sảnh?",
			List.of("<gray>Bạn sẽ bỏ cuộc ván này.</gray>", "<red>Ván này tính là thua.</red>"),
			() -> {
				if (spawn != null) {
					spawn.confirm(player);
				}
			},
			() -> DeathSwapText.send(player, DeathSwapText.INFO + "Đã hủy rời ván."));
	}

	/** The screen that fits where the player is: their room, else the room list. */
	public void openMain(Player player) {
		Room room = rooms.roomOf(player);
		if (room != null) {
			openRoom(player, room.id());
		} else {
			openBrowser(player, 0);
		}
	}

	public void openSettingsFor(Player player) {
		Room room = rooms.roomOf(player);
		if (room == null) {
			fail(player, "Bạn chưa ở trong phòng nào.");
			return;
		}
		openSettings(player, room.id(), 0);
	}
}
