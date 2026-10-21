package dev.belikhun.luna.deathswap.room;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.deathswap.DeathSwapSettings;
import dev.belikhun.luna.deathswap.DeathSwapSidebar;
import dev.belikhun.luna.deathswap.DeathSwapStats;
import dev.belikhun.luna.deathswap.DeathSwapText;
import dev.belikhun.luna.deathswap.LunaDeathSwapPlugin;
import dev.belikhun.luna.deathswap.lobby.LobbyService;
import dev.belikhun.luna.deathswap.world.RoundWorldService;
import dev.belikhun.luna.deathswap.world.SpawnPregen;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * All rooms, who is in which, and which world belongs to which. Persistent
 * rooms (and their settings) survive restarts in rooms.yml; the rest close
 * once empty, as manhunt's rooms disband. Also owns the per-player displays,
 * since what a player sees depends on the room they are in.
 *
 * Main thread only.
 */
public final class RoomManager {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,16}");

	private final LunaDeathSwapPlugin plugin;
	private final LunaLogger logger;
	private final DeathSwapStats stats;
	private final RoundWorldService worlds;
	private final LobbyService lobby;
	private final DeathSwapSidebar sidebar = new DeathSwapSidebar();
	private final File file;
	private final SpawnPregen pregen;

	private final Map<String, Room> rooms = new LinkedHashMap<>();
	private final Map<UUID, String> playerRoom = new HashMap<>();
	private final Map<UUID, String> worldRoom = new HashMap<>();

	private DeathSwapSettings settings;
	private BukkitTask ticker;
	private BukkitTask displayTicker;

	public RoomManager(LunaDeathSwapPlugin plugin, LunaLogger logger, DeathSwapStats stats, DeathSwapSettings settings,
			RoundWorldService worlds, LobbyService lobby) {
		this.plugin = plugin;
		this.logger = logger;
		this.stats = stats;
		this.settings = settings;
		this.worlds = worlds;
		this.lobby = lobby;
		this.file = new File(plugin.getDataFolder(), "rooms.yml");
		this.pregen = new SpawnPregen(plugin, logger, settings.pregen());
	}

	public SpawnPregen pregen() {
		return pregen;
	}

	// ------------------------------------------------------------------
	// accessors for rooms

	public LunaDeathSwapPlugin plugin() {
		return plugin;
	}

	public LunaLogger logger() {
		return logger;
	}

	public DeathSwapStats stats() {
		return stats;
	}

	public RoundWorldService worlds() {
		return worlds;
	}

	public DeathSwapSettings settings() {
		return settings;
	}

	public String playerDisplay(Player player) {
		return plugin.playerDisplay(player);
	}

	// ------------------------------------------------------------------
	// lifecycle

	public void enable() {
		load();
		ensureDefaultRoom();
		ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
		restartDisplayTicker();
		for (Player player : Bukkit.getOnlinePlayers()) {
			handleJoin(player, false);
		}
	}

	public void disable() {
		if (ticker != null) {
			ticker.cancel();
		}
		if (displayTicker != null) {
			displayTicker.cancel();
		}
		for (Room room : new ArrayList<>(rooms.values())) {
			// STARTING also holds a prepared world now
			if (room.inRound() || room.state() == Room.State.STARTING) {
				room.endRound();
			}
		}
		save();
		sidebar.clear();
		stats.saveNow();
	}

	public void reload(DeathSwapSettings settings) {
		this.settings = settings;
		pregen.setOptions(settings.pregen());
		ensureDefaultRoom();
		restartDisplayTicker();
	}

	private void restartDisplayTicker() {
		if (displayTicker != null) {
			displayTicker.cancel();
		}
		displayTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 5L, settings.scoreboardUpdateTicks());
	}

	private void tick() {
		for (Room room : new ArrayList<>(rooms.values())) {
			try {
				room.tick();
			} catch (RuntimeException exception) {
				logger.error("Room " + room.id() + " failed to tick.", exception);
			}
			if (!room.settings().persistent && room.isEmptyAndIdle() && !room.id().equals(settings.defaultRoom())) {
				rooms.remove(room.id());
				logger.info("Closed empty room " + room.id() + ".");
			}
		}
	}

	// ------------------------------------------------------------------
	// rooms

	public Collection<Room> rooms() {
		return Collections.unmodifiableCollection(rooms.values());
	}

	public Room get(String id) {
		return id == null ? null : rooms.get(id.toLowerCase(Locale.ROOT));
	}

	public Room roomOf(Player player) {
		return get(playerRoom.get(player.getUniqueId()));
	}

	public Room roomOf(UUID playerId) {
		return get(playerRoom.get(playerId));
	}

	public Room roomByWorld(World world) {
		return world == null ? null : get(worldRoom.get(world.getUID()));
	}

	/** The room whose rules apply to this player right now: the world they stand in, else the room they are in. */
	public Room roomFor(Player player) {
		Room byWorld = roomByWorld(player.getWorld());
		return byWorld != null ? byWorld : roomOf(player);
	}

	void indexWorld(Room room, World world) {
		worldRoom.put(world.getUID(), room.id());
	}

	void unindexWorld(World world) {
		worldRoom.remove(world.getUID());
	}

	public static boolean validId(String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	/** Returns null on success, else why not. */
	public String create(String id, UUID owner, String displayName, boolean persistent) {
		String key = id.toLowerCase(Locale.ROOT);
		if (!validId(key)) {
			return "ID phòng chỉ gồm a-z, 0-9, _ và -, tối đa 16 ký tự.";
		}
		if (rooms.containsKey(key)) {
			return "Phòng " + key + " đã tồn tại.";
		}
		RoomSettings roomSettings = settings.roomDefaults().copy();
		roomSettings.displayName = displayName == null ? "" : displayName;
		roomSettings.persistent = persistent;
		rooms.put(key, new Room(this, key, roomSettings, owner));
		save();
		logger.audit("Room " + key + " created" + (owner == null ? "" : " by " + Bukkit.getOfflinePlayer(owner).getName()) + ".");
		return null;
	}

	/** Close a room: end its round, send its members to the lobby without a room. */
	public boolean delete(String id, String reason) {
		Room room = get(id);
		if (room == null) {
			return false;
		}
		if (room.inRound() || room.state() == Room.State.STARTING) {
			room.stop(reason == null ? "phòng đã đóng" : reason);
		}
		room.broadcast(DeathSwapText.WARN + "Phòng " + room.tag() + " đã đóng" + (reason == null ? "." : ": " + DeathSwapText.white(reason)));
		for (UUID playerId : new ArrayList<>(room.members())) {
			playerRoom.remove(playerId);
			Player player = Bukkit.getPlayer(playerId);
			if (player != null) {
				sendToLobby(player);
			}
		}
		rooms.remove(room.id());
		save();
		logger.audit("Room " + room.id() + " deleted.");
		return true;
	}

	/** Admins manage every room; a player manages the room they own. */
	public boolean canManage(org.bukkit.command.CommandSender sender, Room room) {
		if (sender.hasPermission("lunadeathswap.admin")) {
			return true;
		}
		return sender instanceof Player player && player.getUniqueId().equals(room.owner());
	}

	/**
	 * Change one room setting the one way both the command and the GUI use:
	 * validate, save rooms.yml, tell the room. Returns null on success, else why not.
	 */
	public String changeSetting(org.bukkit.command.CommandSender sender, Room room, String key, String value) {
		if (key.equalsIgnoreCase("persistent") && !sender.hasPermission("lunadeathswap.admin")) {
			return "Chỉ quản trị viên mới đổi được thiết lập này.";
		}
		String error = room.settings().set(key, value);
		if (error != null) {
			return error;
		}
		save();
		room.broadcast(DeathSwapText.INFO + "Thiết lập phòng đổi: " + DeathSwapText.color(DeathSwapText.COMMAND, key.toLowerCase(Locale.ROOT))
			+ " = " + DeathSwapText.white(room.settings().get(key)));
		refreshRoom(room);
		return null;
	}

	public Room.JoinResult join(Player player, Room room) {
		Room current = roomOf(player);
		if (current == room) {
			return Room.JoinResult.ALREADY;
		}
		if (room.isFull()) {
			return Room.JoinResult.FULL;
		}
		if (current != null) {
			leave(player, "chuyển sang phòng khác");
		}
		Room.JoinResult result = room.addMember(player);
		if (result == Room.JoinResult.JOINED) {
			playerRoom.put(player.getUniqueId(), room.id());
			if (room.owner() == null) {
				room.setOwner(player.getUniqueId());
			}
			if (lobby.isIn(player)) {
				sendToLobby(player);
			}
			refreshDisplay(player);
		}
		return result;
	}

	public boolean leave(Player player, String reason) {
		Room room = roomOf(player);
		if (room == null) {
			return false;
		}
		room.removeMember(player, reason);
		playerRoom.remove(player.getUniqueId());
		if (room.world() != null && player.getWorld().equals(room.world()) || lobby.isIn(player)) {
			sendToLobby(player);
		}
		refreshDisplay(player);
		return true;
	}

	private void ensureDefaultRoom() {
		String id = settings.defaultRoom();
		if (id == null || id.isBlank() || rooms.containsKey(id)) {
			return;
		}
		create(id, null, settings.defaultRoomName(), true);
	}

	// ------------------------------------------------------------------
	// players

	/** Into the lobby with the hotbar that matches their room situation. */
	public void sendToLobby(Player player) {
		Room room = roomOf(player);
		lobby.enter(player, room != null, room != null && room.isSittingOut(player.getUniqueId()));
	}

	/** Refresh the lobby hotbar of a player standing in the lobby. */
	public void sendToLobbyIfThere(Player player) {
		if (lobby.isIn(player)) {
			Room room = roomOf(player);
			lobby.giveItems(player, room != null, room != null && room.isSittingOut(player.getUniqueId()));
		}
	}

	public void setOwner(Room room, UUID owner) {
		room.setOwner(owner);
		save();
	}

	public void handleJoin(Player player, boolean greet) {
		sendToLobby(player);

		if (settings.autoJoin() && roomOf(player) == null) {
			Room room = get(settings.defaultRoom());
			if (room != null && !room.isFull()) {
				join(player, room);
			}
		}

		if (greet) {
			Room room = roomOf(player);
			DeathSwapText.send(player, " ");
			DeathSwapText.send(player, "Chào mừng đến với <b><gradient:#FF7EE3:#ED80FF:#C27EFF:#EB86FF:#8768CC>LUNA</gradient></b> " + DeathSwapText.BRAND + "!");
			if (room == null) {
				DeathSwapText.send(player, DeathSwapText.INFO + "Chọn phòng bằng " + DeathSwapText.command("/ds rooms") + " hoặc ngôi sao trên thanh công cụ.");
			} else if (room.inRound()) {
				DeathSwapText.send(player, DeathSwapText.INFO + "Bạn đang ở phòng " + room.tag() + ". Một ván đang diễn ra; xem bằng "
					+ DeathSwapText.command("/ds watch") + ".");
			} else {
				DeathSwapText.send(player, DeathSwapText.INFO + "Bạn đang ở phòng " + room.tag() + ". Ván mới tự bắt đầu khi đủ "
					+ DeathSwapText.white(String.valueOf(room.settings().minPlayers)) + " người.");
			}
			DeathSwapText.send(player, " ");
			player.playSound(player, org.bukkit.Sound.UI_TOAST_IN, 0.7f, 1f);
		}
		refreshDisplay(player);
	}

	/** On quit the player leaves their room (a living player is eliminated); the next join re-runs auto-join. */
	public void handleQuit(Player player) {
		sidebar.forget(player.getUniqueId());
		Room room = roomOf(player);
		if (room != null) {
			room.removeMember(player, room.isAlive(player.getUniqueId()) ? "đã rời trận và bị loại." : "đã rời máy chủ.");
			playerRoom.remove(player.getUniqueId());
		}
	}

	public String handleDeath(Player player, String vanillaPlain) {
		Room room = roomByWorld(player.getWorld());
		return room == null ? null : room.handleDeath(player, vanillaPlain);
	}

	public Location respawnLocation(Player player) {
		Room room = roomByWorld(player.getWorld());
		Location spot = room == null ? null : room.respawnLocation(player);
		return spot != null ? spot : lobby.spawn();
	}

	public void handleRespawned(Player player) {
		Room room = roomByWorld(player.getWorld());
		if (room != null) {
			room.handleRespawned(player);
		} else if (lobby.isIn(player)) {
			sendToLobby(player);
		}
	}

	// ------------------------------------------------------------------
	// displays

	void refreshRoom(Room room) {
		for (Player player : room.audience()) {
			refreshDisplay(player);
		}
	}

	private void refreshAll() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			refreshDisplay(player);
		}
	}

	public void refreshDisplay(Player player) {
		Room room = roomFor(player);
		Map<String, String> values = placeholders(player, room);
		String key = room == null ? "hub" : room.templateKey(player);

		if (settings.scoreboardEnabled()) {
			DeathSwapSettings.Template template = settings.templates().get(key);
			if (template != null) {
				List<String> lines = new ArrayList<>();
				addLines(lines, template.header(), player, values);
				if (room != null && !template.aliveLine().isEmpty() && template.aliveMax() > 0) {
					List<String> names = room.aliveNames();
					List<UUID> ids = room.aliveIds();
					if (names.isEmpty()) {
						addLine(lines, template.aliveEmpty(), player, values);
					}
					for (int index = 0; index < Math.min(names.size(), template.aliveMax()); index++) {
						Map<String, String> line = new HashMap<>(values);
						line.put("alive_name", DeathSwapText.escape(names.get(index)));
						line.put("alive_hearts", index < ids.size() ? room.heartsChip(ids.get(index)) : "");
						addLine(lines, template.aliveLine(), player, line);
					}
					if (names.size() > template.aliveMax()) {
						addLine(lines, " <gray>... và " + (names.size() - template.aliveMax()) + " người khác</gray> ", player, values);
					}
				}
				if (room == null && !template.aliveLine().isEmpty() && template.aliveMax() > 0) {
					// the hub lists rooms in the repeated block
					int shown = 0;
					for (Room listed : rooms.values()) {
						if (listed.settings().visibility != RoomSettings.Visibility.PUBLIC || shown >= template.aliveMax()) {
							continue;
						}
						Map<String, String> line = new HashMap<>(values);
						line.put("list_room_name", DeathSwapText.escape(listed.displayName()));
						line.put("list_room_state", listed.stateDisplay());
						line.put("list_room_members", listed.members().size() + "/" + listed.settings().maxPlayers);
						addLine(lines, template.aliveLine(), player, line);
						shown++;
					}
					if (shown == 0) {
						addLine(lines, template.aliveEmpty(), player, values);
					}
				}
				addLines(lines, template.footer(), player, values);
				sidebar.render(player, DeathSwapText.papi(player, DeathSwapText.fill(template.title(), values)), lines);
			}
		} else {
			sidebar.remove(player);
		}

		String actionbar = settings.actionbar().get(key);
		if (actionbar != null && !actionbar.isBlank()) {
			player.sendActionBar(DeathSwapText.mm(DeathSwapText.papi(player, DeathSwapText.fill(actionbar, values))));
		}
	}

	private void addLines(List<String> out, List<String> templates, Player player, Map<String, String> values) {
		for (String template : templates) {
			addLine(out, template, player, values);
		}
	}

	private void addLine(List<String> out, String template, Player player, Map<String, String> values) {
		String line = DeathSwapText.papi(player, DeathSwapText.fill(template, values));
		if (!line.contains("[skip]")) {
			out.add(line);
		}
	}

	/** Every %placeholder% the templates can use, for one viewer. */
	public Map<String, String> placeholders(Player player, Room room) {
		Map<String, String> values = new HashMap<>();
		DeathSwapStats.Entry entry = stats.get(player.getUniqueId());
		RoundWorldService.Status world = worlds.status();

		values.put("player_display", playerDisplay(player));
		values.put("footer", settings.footer());
		values.put("rooms_count", String.valueOf(rooms.size()));
		values.put("world_pool_chip", worldPoolChip(world));
		values.put("stats_wins_chip", DeathSwapText.color(DeathSwapText.ACCENT_GOLD, "👑") + " <white>" + entry.wins + "</white> <gray>thắng</gray>");
		values.put("stats_games_chip", DeathSwapText.color(DeathSwapText.ACCENT_BLUE, "⚔") + " <white>" + entry.games + "</white> <gray>ván</gray>");
		values.put("stats_traps_chip", DeathSwapText.color(DeathSwapText.ACCENT_FIRE, "🔥") + " <white>" + entry.traps + "</white> <gray>bẫy</gray>");
		values.put("stats_best_chip", DeathSwapText.color(DeathSwapText.ACCENT_TIME, "⌚") + " <white>" + DeathSwapText.clock(entry.longestSurviveSeconds) + "</white>");

		boolean generating = room != null && room.state() == Room.State.PREPARING && room.world() == null && world.busy();
		boolean preparing = room != null && room.state() == Room.State.PREPARING;
		long eta = preparing ? room.prepareEtaSeconds() : -1L;
		values.put("prepare_bar_chip", progressBar(preparing ? room.prepareFraction() : 0D, 20));
		values.put("prepare_eta_chip", eta < 0
			? "<yellow>⌛</yellow> <white>đang ước tính...</white>"
			: eta == 0
			? "<yellow>⌛</yellow> <white>sắp xong</white>"
			: "<yellow>⌛</yellow> <white>~" + DeathSwapText.clock(eta) + "</white>");

		if (room != null) {
			room.fillPlaceholders(player, values);
			if (generating) {
				values.put("preparing_stage", DeathSwapText.escape(world.stageLabel()));
			}
		}
		return values;
	}

	private static String progressBar(double fraction, int cells) {
		double clamped = Math.max(0D, Math.min(1D, fraction));
		int filled = (int) Math.round(clamped * cells);
		// a thin bar (▍), as in manhunt; empty cells keep the same glyph in dark gray
		return "<gradient:#7ED6FF:#A78BFA>" + "▍".repeat(filled) + "</gradient><dark_gray>" + "▍".repeat(cells - filled)
			+ "</dark_gray> <white>" + Math.round(clamped * 100) + "%</white>";
	}

	private static String worldPoolChip(RoundWorldService.Status world) {
		if (world.ready() > 0) {
			return DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "◎") + " <white>Thế giới sẵn sàng</white>";
		}
		if (world.busy()) {
			return "<yellow>◎</yellow> <white>Đang tạo</white> <gray>" + Math.round(world.fraction() * 100) + "%</gray>";
		}
		return "<gray>◎ Tạo khi bắt đầu</gray>";
	}

	// ------------------------------------------------------------------
	// persistence

	private void load() {
		rooms.clear();
		if (!file.exists()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection section = yaml.getConfigurationSection("rooms");
		if (section == null) {
			return;
		}
		for (String id : section.getKeys(false)) {
			ConfigurationSection roomSection = section.getConfigurationSection(id);
			if (roomSection == null || !validId(id)) {
				continue;
			}
			RoomSettings roomSettings = settings.roomDefaults().copy().load(roomSection.getConfigurationSection("settings"));
			roomSettings.persistent = true;
			UUID owner = null;
			try {
				String raw = roomSection.getString("owner", "");
				owner = raw.isBlank() ? null : UUID.fromString(raw);
			} catch (IllegalArgumentException ignored) {
				// no owner
			}
			rooms.put(id, new Room(this, id, roomSettings, owner));
		}
	}

	/** Persistent rooms only; temporary rooms are gone after a restart by design. */
	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.options().setHeader(List.of("Persistent LunaDeathSwap rooms. Edit with /ds room, not by hand while the server runs."));
		for (Room room : rooms.values()) {
			if (!room.settings().persistent) {
				continue;
			}
			ConfigurationSection section = yaml.createSection("rooms." + room.id());
			section.set("owner", room.owner() == null ? "" : room.owner().toString());
			room.settings().save(section.createSection("settings"));
		}
		try {
			yaml.save(file);
		} catch (IOException exception) {
			logger.warn("Could not save rooms.yml: " + exception.getMessage());
		}
	}
}
