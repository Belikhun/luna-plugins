package dev.belikhun.luna.deathswap.room;

import dev.belikhun.luna.deathswap.DeathSwapStats;
import dev.belikhun.luna.deathswap.DeathSwapText;
import dev.belikhun.luna.deathswap.lobby.LobbyService;
import dev.belikhun.luna.deathswap.world.RoundWorldService;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One DeathSwap room: its members, its settings and at most one round at a
 * time on a world of its own. Members wait in the shared lobby between
 * rounds. Messages go to the room's audience only (members, plus anyone
 * watching its world), so rooms never talk over each other.
 *
 * Main thread only.
 */
public final class Room {
	public enum State {
		WAITING,
		STARTING,
		PREPARING,
		PLAYING,
		FINISHED
	}

	private record TrapCredit(UUID by, long at) {
	}

	private final RoomManager manager;
	private final String id;
	private final RoomSettings settings;
	private UUID owner;

	private State state = State.WAITING;
	private final Set<UUID> members = new LinkedHashSet<>();
	private final Set<UUID> sittingOut = new HashSet<>();
	/** Players who walked back to the lobby during this round and should stay there. */
	private final Set<UUID> lobbyBound = new HashSet<>();
	private final Set<UUID> participants = new LinkedHashSet<>();
	private final Set<UUID> alive = new LinkedHashSet<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, TrapCredit> credits = new HashMap<>();
	private final Map<UUID, Integer> roundTraps = new HashMap<>();
	private final Map<UUID, Location> deathSpots = new HashMap<>();

	private World world;
	private boolean forcedStart;
	private int countdownLeft;
	/** Countdown to run once the world is prepared (the world is made first, then the countdown). */
	private int pendingCountdown = 10;
	/** Spawn points found during preparation, used when the countdown ends. */
	private Map<UUID, CompletableFuture<Location>> preparedSpots = new LinkedHashMap<>();
	private boolean shrinkStarted;
	private boolean finalCloseStarted;
	/** Order fixed at launch, for the ring and pairs swap patterns. */
	private final List<UUID> swapOrder = new ArrayList<>();
	/** LifeSwap: each player's current max health (half-hearts). */
	private final Map<UUID, Double> lifeHealth = new HashMap<>();
	/** LifeSwap: deaths that keep their inventory (not the eliminating one). */
	private final Set<UUID> keepInventory = new HashSet<>();
	private long startedAt;
	private long finishedAt;
	private long nextSwapAt;
	private int swaps;
	private int rounds;
	private UUID winner;
	private volatile String preparingStage = "";
	private int roundToken;

	/** Preparation stages, each weighted into one overall progress bar (manhunt-style). */
	private enum Prep {
		WORLD,
		SPOTS,
		PREGEN
	}

	private static final double[] WEIGHTS_POOLED = {0.05D, 0.10D, 0.85D};
	private static final double[] WEIGHTS_GENERATING = {0.55D, 0.05D, 0.40D};
	private volatile Prep prep = Prep.WORLD;
	/** Progress inside the current stage, 0..1; written from Chunky's threads too. */
	private volatile double prepProgress;
	private double[] prepWeights = WEIGHTS_POOLED;
	private long prepareStartedAt;
	private int swapInterval = 1;
	private BossBar swapBar;
	private final Set<UUID> swapBarViewers = new HashSet<>();

	Room(RoomManager manager, String id, RoomSettings settings, UUID owner) {
		this.manager = manager;
		this.id = id;
		this.settings = settings;
		this.owner = owner;
	}

	// ------------------------------------------------------------------
	// identity and read-only view

	public String id() {
		return id;
	}

	public RoomSettings settings() {
		return settings;
	}

	public UUID owner() {
		return owner;
	}

	void setOwner(UUID owner) {
		this.owner = owner;
	}

	public String displayName() {
		return settings.displayName == null || settings.displayName.isBlank() ? id : settings.displayName;
	}

	/** The room's name as chat shows it: a tag that runs /ds join on click. */
	public String tag() {
		return "<click:run_command:'/ds join " + id + "'><hover:show_text:'<gray>Bấm để vào phòng</gray>'>"
			+ "<gradient:#7ED6FF:#A78BFA>⌂ " + DeathSwapText.escape(displayName()) + "</gradient></hover></click>";
	}

	public State state() {
		return state;
	}

	public boolean inRound() {
		return state == State.PREPARING || state == State.PLAYING || state == State.FINISHED;
	}

	public Set<UUID> members() {
		return Collections.unmodifiableSet(members);
	}

	public boolean isMember(UUID playerId) {
		return members.contains(playerId);
	}

	public boolean isAlive(UUID playerId) {
		return alive.contains(playerId);
	}

	public boolean isParticipant(UUID playerId) {
		return participants.contains(playerId);
	}

	public boolean isSittingOut(UUID playerId) {
		return sittingOut.contains(playerId);
	}

	public int aliveCount() {
		return alive.size();
	}

	public int participantCount() {
		return participants.size();
	}

	public int swaps() {
		return swaps;
	}

	public int rounds() {
		return rounds;
	}

	public int countdownLeft() {
		return countdownLeft;
	}

	public World world() {
		return world;
	}

	public String preparingStage() {
		return preparingStage;
	}

	/** Overall preparation progress, 0..1: getting the world, finding spawns, generating the spawn areas. */
	public double prepareFraction() {
		if (state != State.PREPARING) {
			return state == State.PLAYING || state == State.FINISHED ? 1D : 0D;
		}
		Prep stage = prep;
		double inside = prepProgress;
		if (stage == Prep.WORLD && world == null) {
			RoundWorldService.Status status = manager.worlds().status();
			if (status.busy()) {
				inside = status.fraction();
			}
		}
		double done = 0D;
		for (int index = 0; index < stage.ordinal(); index++) {
			done += prepWeights[index];
		}
		double fraction = done + prepWeights[stage.ordinal()] * Math.max(0D, Math.min(1D, inside));
		return Math.min(0.99D, fraction);
	}

	/** Seconds left in preparation, extrapolated from the progress so far; -1 while too early to tell. */
	public long prepareEtaSeconds() {
		if (state != State.PREPARING || prepareStartedAt <= 0) {
			return -1L;
		}
		if (prep == Prep.WORLD && world == null) {
			RoundWorldService.Status status = manager.worlds().status();
			if (status.busy()) {
				// the generator knows its own ETA; add a rough share for the spawn areas
				return status.etaSeconds() + Math.round(status.etaSeconds() * prepWeights[Prep.PREGEN.ordinal()] / prepWeights[Prep.WORLD.ordinal()]);
			}
		}
		double fraction = prepareFraction();
		double elapsed = (System.currentTimeMillis() - prepareStartedAt) / 1000D;
		if (fraction < 0.08D || elapsed < 1.5D) {
			return -1L;
		}
		return Math.max(0L, Math.round(elapsed * (1D - fraction) / fraction));
	}

	public long secondsUntilSwap() {
		return state == State.PLAYING ? Math.max(0, (nextSwapAt - System.currentTimeMillis() + 999) / 1000) : -1;
	}

	public boolean swapTimerVisible() {
		boolean shown = swaps == 0 ? settings.showTimer && !settings.hideFirstSwap : settings.showTimer;
		return shown || secondsUntilSwap() <= settings.swapWarning;
	}

	public long elapsedSeconds() {
		if (startedAt <= 0) {
			return 0;
		}
		long end = state == State.FINISHED && finishedAt > 0 ? finishedAt : System.currentTimeMillis();
		return Math.max(0, (end - startedAt) / 1000);
	}

	public int roundTraps(UUID playerId) {
		return roundTraps.getOrDefault(playerId, 0);
	}

	public String winnerName() {
		return winner == null ? null : names.getOrDefault(winner, "?");
	}

	public List<String> aliveNames() {
		List<String> list = new ArrayList<>();
		for (UUID playerId : alive) {
			list.add(names.getOrDefault(playerId, "?"));
		}
		return list;
	}

	/** Direct player damage is on: always (pvp), or once the arena closes in (shrink-pvp). */
	public boolean pvpActive() {
		return settings.pvp || (settings.shrinkPvp && shrinkStarted && state == State.PLAYING);
	}

	public List<UUID> aliveIds() {
		return new ArrayList<>(alive);
	}

	/**
	 * A survivor's maximum hearts right now (LifeSwap changes it during the
	 * round), or -1 when they are not alive in this round.
	 */
	public double maxHeartsOf(UUID playerId) {
		if (!alive.contains(playerId) || (state != State.PLAYING && state != State.FINISHED)) {
			return -1D;
		}
		Player player = Bukkit.getPlayer(playerId);
		org.bukkit.attribute.AttributeInstance attribute = player == null ? null : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
		double halfHearts = attribute != null && !player.isDead()
			? attribute.getValue()
			: lifeHealth.getOrDefault(playerId, settings.maxHearts * 2D);
		return halfHearts / 2D;
	}

	/** "❤ 12" in MiniMessage, or "" when the player is not alive in this round. */
	public String heartsChip(UUID playerId) {
		double max = maxHeartsOf(playerId);
		if (max < 0) {
			return "";
		}
		String value = max == Math.floor(max) ? String.valueOf((long) max) : String.format(Locale.ROOT, "%.1f", max);
		return "<#ff5c74>❤</#ff5c74> <white>" + value + "</white>";
	}

	public List<String> memberNames() {
		List<String> list = new ArrayList<>();
		for (UUID playerId : members) {
			list.add(names.getOrDefault(playerId, "?"));
		}
		return list;
	}

	public List<Player> eligiblePlayers() {
		List<Player> list = new ArrayList<>();
		for (UUID playerId : members) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && !sittingOut.contains(playerId)) {
				list.add(player);
			}
		}
		return list;
	}

	public boolean isFull() {
		return members.size() >= settings.maxPlayers;
	}

	/** Players in this room's world who are not playing in it. */
	public boolean isWatching(Player player) {
		return world != null && player.getWorld().equals(world) && !alive.contains(player.getUniqueId());
	}

	public boolean isEmptyAndIdle() {
		return members.isEmpty() && !inRound() && state != State.STARTING;
	}

	/** Members, and anyone else standing in this room's world. */
	public Collection<Player> audience() {
		Set<Player> out = new LinkedHashSet<>();
		for (UUID playerId : members) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null) {
				out.add(player);
			}
		}
		if (world != null) {
			out.addAll(world.getPlayers());
		}
		return out;
	}

	public void broadcast(String miniMessage) {
		var component = DeathSwapText.mm(miniMessage);
		for (Player player : audience()) {
			player.sendMessage(component);
		}
		Bukkit.getConsoleSender().sendMessage(DeathSwapText.mm("<gray>[" + DeathSwapText.escape(id) + "]</gray> " + miniMessage));
	}

	private void broadcastBlock(String... lines) {
		broadcast(" ");
		for (String line : lines) {
			broadcast(line);
		}
		broadcast(" ");
	}

	// ------------------------------------------------------------------
	// membership

	public enum JoinResult {
		JOINED,
		ALREADY,
		FULL
	}

	JoinResult addMember(Player player) {
		UUID playerId = player.getUniqueId();
		if (members.contains(playerId)) {
			return JoinResult.ALREADY;
		}
		if (isFull()) {
			return JoinResult.FULL;
		}
		members.add(playerId);
		names.put(playerId, player.getName());
		sittingOut.remove(playerId);
		broadcast(DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "+") + " " + manager.playerDisplay(player)
			+ " <gray>vào phòng</gray> <white>(" + members.size() + "/" + settings.maxPlayers + ")</white>");
		if (inRound()) {
			DeathSwapText.send(player, DeathSwapText.INFO + "Phòng đang có ván. Bạn sẽ chơi từ ván sau; xem bằng " + DeathSwapText.command("/ds watch") + ".");
		}
		return JoinResult.JOINED;
	}

	/** Out of the room; a living player forfeits. Does not move them. */
	void removeMember(Player player, String reason) {
		UUID playerId = player.getUniqueId();
		if (!members.remove(playerId)) {
			return;
		}
		sittingOut.remove(playerId);
		lobbyBound.remove(playerId);
		if (state == State.PLAYING && alive.contains(playerId)) {
			eliminate(playerId);
			broadcast("<red>☠</red> " + manager.playerDisplay(player) + " <gray>" + reason + "</gray>");
			Bukkit.getScheduler().runTaskLater(manager.plugin(), this::checkWin, 1L);
		} else {
			broadcast(DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "-") + " " + manager.playerDisplay(player)
				+ " <gray>" + reason + "</gray>");
		}
		if (owner != null && owner.equals(playerId)) {
			owner = members.isEmpty() ? null : members.iterator().next();
		}
	}

	public boolean setSitOut(UUID playerId, boolean sitOut) {
		if (!members.contains(playerId)) {
			return false;
		}
		return sitOut ? sittingOut.add(playerId) : sittingOut.remove(playerId);
	}

	// ------------------------------------------------------------------
	// operator actions

	public boolean forceStart(int seconds) {
		if (state == State.STARTING) {
			// the world is already prepared; just retime the countdown
			countdownLeft = Math.max(3, seconds);
			broadcast(DeathSwapText.WAIT + "Ván mới bắt đầu sau " + DeathSwapText.white(String.valueOf(countdownLeft)) + " giây.");
			return true;
		}
		if (state != State.WAITING) {
			return false;
		}
		forcedStart = true;
		pendingCountdown = Math.max(3, seconds);
		prepare();
		return true;
	}

	public boolean stop(String reason) {
		if (state == State.WAITING) {
			return false;
		}

		String suffix = reason == null || reason.isBlank() ? "." : ": " + DeathSwapText.white(reason);
		if (state == State.STARTING) {
			// the countdown runs on an already prepared world; hand it back
			broadcast(DeathSwapText.WARN + "Đếm ngược đã bị hủy" + suffix);
			endRound();
			return true;
		}

		broadcast(DeathSwapText.WARN + "Ván đấu đã bị dừng" + suffix);
		endRound();
		return true;
	}

	public boolean forceSwap() {
		if (state != State.PLAYING) {
			return false;
		}
		swap();
		return true;
	}

	/** Back to the lobby but still in the room; a living player forfeits. */
	public void returnToLobby(Player player) {
		UUID playerId = player.getUniqueId();
		if (state == State.PLAYING && alive.contains(playerId)) {
			eliminate(playerId);
			broadcast("<red>☠</red> " + manager.playerDisplay(player) + " <gray>đã bỏ cuộc.</gray>");
			Bukkit.getScheduler().runTaskLater(manager.plugin(), this::checkWin, 1L);
		}
		if (world != null) {
			lobbyBound.add(playerId);
		}
		manager.sendToLobby(player);
	}

	public boolean spectate(Player player) {
		if (world == null || (state != State.PLAYING && state != State.FINISHED) || alive.contains(player.getUniqueId())) {
			return false;
		}
		lobbyBound.remove(player.getUniqueId());
		LobbyService.resetPlayer(player, GameMode.SPECTATOR);
		player.teleportAsync(spectateTarget(), PlayerTeleportEvent.TeleportCause.PLUGIN);
		return true;
	}

	/** Living players a watcher can follow, in join order. */
	public List<Player> spectatable() {
		List<Player> list = new ArrayList<>();
		if (world == null || state != State.PLAYING) {
			return list;
		}
		for (UUID playerId : alive) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && player.getWorld().equals(world)) {
				list.add(player);
			}
		}
		return list;
	}

	/**
	 * Put a non-playing viewer behind a living player's eyes. Null on success,
	 * else the reason. The camera lock survives until the viewer sneaks out of
	 * it (vanilla), so a swap teleports the camera along with the target.
	 */
	public String spectatePlayer(Player viewer, Player target) {
		if (state != State.PLAYING || world == null) {
			return "Phòng chưa có ván đang diễn ra.";
		}
		if (alive.contains(viewer.getUniqueId())) {
			return "Bạn đang chơi mà!";
		}
		if (!alive.contains(target.getUniqueId()) || !target.getWorld().equals(world)) {
			return target.getName() + " không còn trong ván.";
		}
		lobbyBound.remove(viewer.getUniqueId());
		if (viewer.getGameMode() != GameMode.SPECTATOR || !viewer.getWorld().equals(world)) {
			LobbyService.resetPlayer(viewer, GameMode.SPECTATOR);
		}
		viewer.setSpectatorTarget(null);
		viewer.teleportAsync(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(done -> {
			if (done && viewer.isOnline() && target.isOnline() && viewer.getGameMode() == GameMode.SPECTATOR
				&& viewer.getWorld().equals(target.getWorld())) {
				viewer.setSpectatorTarget(target);
				DeathSwapText.send(viewer, DeathSwapText.INFO + "Đang xem " + DeathSwapText.white(target.getName())
					+ ". <gray>Shift để bay tự do,</gray> " + DeathSwapText.command("/ds spec") + " <gray>để đổi người.</gray>");
			}
		});
		return null;
	}

	// ------------------------------------------------------------------
	// the clock (once a second)

	void tick() {
		switch (state) {
			case WAITING -> {
				if (settings.autoStart && eligiblePlayers().size() >= settings.minPlayers) {
					forcedStart = false;
					pendingCountdown = settings.startCountdown;
					prepare();
				}
			}
			case STARTING -> tickStarting();
			case PLAYING -> tickPlaying();
			default -> {
			}
		}
	}

	/** The world and spawn areas are ready: count down, then send everyone in. */
	private void beginCountdown(int seconds) {
		state = State.STARTING;
		countdownLeft = seconds;
		broadcast(DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "✔") + " Thế giới đã sẵn sàng! Ván mới bắt đầu sau "
			+ DeathSwapText.white(String.valueOf(seconds)) + " giây.");
		for (Player player : audience()) {
			player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
		}
		manager.refreshRoom(this);
	}

	private void tickStarting() {
		int needed = forcedStart ? 1 : settings.minPlayers;
		long ready = participants.stream().filter(this::stillPlaying).count();
		if (ready < needed) {
			broadcast(DeathSwapText.WARN + "Không đủ người chơi, đã hủy đếm ngược.");
			endRound();
			return;
		}

		countdownLeft--;
		if (countdownLeft <= 0) {
			launch(world, preparedSpots, null);
			return;
		}

		if (countdownLeft <= 5 || countdownLeft == 10) {
			String color = countdownLeft <= 3 ? DeathSwapText.ACCENT_DANGER : DeathSwapText.ACCENT_GOLD;
			for (Player player : audience()) {
				DeathSwapText.title(player, DeathSwapText.color(color, "<b>" + countdownLeft + "</b>"), "<gray>Ván mới sắp bắt đầu</gray>", 1100);
				player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
			}
		}
	}

	/**
	 * The closing arena (shrink-after / shrink-time / shrink-size): a minute's
	 * warning, then the border jumps in to just outside the farthest survivor
	 * and closes on 0,0 over shrink-time, so a long round (LifeSwap) always ends.
	 */
	private void tickShrink() {
		if (settings.shrinkAfter <= 0 || world == null) {
			return;
		}
		if (shrinkStarted) {
			// last phase: from shrink-size all the way down, a little slower
			if (!finalCloseStarted && settings.shrinkFinalTime > 0
				&& elapsedSeconds() >= settings.shrinkAfter * 60L + settings.shrinkTime * 60L) {
				finalCloseStarted = true;
				world.getWorldBorder().setSize(2D, settings.shrinkFinalTime * 60L);
				broadcast(DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "⚠") + " Vùng chơi tiếp tục thu về "
					+ DeathSwapText.white("0") + " <gray>trong</gray> " + DeathSwapText.white(settings.shrinkFinalTime + " phút") + "<gray>!</gray>");
				manager.logger().info("Room " + id + ": final close to 2 over " + settings.shrinkFinalTime + " min.");
			}
			return;
		}
		long at = settings.shrinkAfter * 60L;
		long elapsed = elapsedSeconds();
		if (elapsed == at - 60) {
			broadcast(DeathSwapText.WARN + "Còn " + DeathSwapText.white("1 phút") + " nữa vùng chơi sẽ bắt đầu thu hẹp về "
				+ DeathSwapText.white("0, 0") + (settings.shrinkTeleport ? " <gray>(mọi người sẽ được đưa vào đấu trường)</gray>" : "") + "!");
			for (Player player : world.getPlayers()) {
				player.playSound(player, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 0.8f);
			}
		}
		if (elapsed < at) {
			return;
		}
		shrinkStarted = true;

		double farthest = 0D;
		for (UUID playerId : alive) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && player.getWorld().equals(world)) {
				Location location = player.getLocation();
				farthest = Math.max(farthest, Math.max(Math.abs(location.getX()), Math.abs(location.getZ())));
			}
		}
		WorldBorder border = world.getWorldBorder();
		double start = Math.min(border.getSize(), Math.max(settings.shrinkSize, farthest * 2D + 64D));
		if (settings.shrinkTeleport) {
			// arena: everyone is brought near 0,0 (generated in the background since launch)
			start = Math.max(settings.shrinkSize, settings.arenaSize);
			teleportIntoArena(start);
		}
		border.setSize(start);
		border.setSize(settings.shrinkSize, settings.shrinkTime * 60L);

		broadcast(DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "⚠") + " Vùng chơi đang thu hẹp! "
			+ DeathSwapText.white(Math.round(start) + " → " + settings.shrinkSize + " ô") + " <gray>trong</gray> "
			+ DeathSwapText.white(settings.shrinkTime + " phút") + "<gray>, tâm ở</gray> " + DeathSwapText.white("0, 0")
			+ "<gray>. Ra ngoài viền là mất máu!</gray>"
			+ (settings.shrinkPvp && !settings.pvp ? " " + DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "⚔ PvP đã bật!") : ""));
		for (Player player : world.getPlayers()) {
			DeathSwapText.title(player, DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "<b>⚠ VÙNG CHƠI THU HẸP</b>"),
				"<gray>Tiến về</gray> <white>0, 0</white>", 3000);
			player.playSound(player, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.2f);
		}
		manager.logger().info("Room " + id + ": border shrinking " + Math.round(start) + " -> " + settings.shrinkSize
			+ " over " + settings.shrinkTime + " min.");
	}

	/**
	 * Spread the survivors on a ring around 0,0 inside the arena (radius 30%
	 * of its size), on top of the ground, with a few seconds of protection.
	 */
	private void teleportIntoArena(double arenaSize) {
		List<Player> players = new ArrayList<>();
		for (UUID playerId : alive) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && !player.isDead() && player.getWorld().equals(world)) {
				players.add(player);
			}
		}
		if (players.isEmpty()) {
			return;
		}
		Collections.shuffle(players);
		double radius = Math.max(8D, arenaSize * 0.3D);
		double base = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
		for (int index = 0; index < players.size(); index++) {
			Player player = players.get(index);
			double angle = base + (Math.PI * 2 * index) / players.size();
			int x = (int) Math.round(Math.cos(angle) * radius);
			int z = (int) Math.round(Math.sin(angle) * radius);
			Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
			// face the centre
			float yaw = (float) Math.toDegrees(Math.atan2(x, -z));
			if (player.isInsideVehicle()) {
				player.leaveVehicle();
			}
			player.setFallDistance(0f);
			player.teleportAsync(new Location(world, x + 0.5, top.getY() + 1, z + 0.5, yaw, 0f), PlayerTeleportEvent.TeleportCause.PLUGIN)
				.thenRun(() -> {
					player.setFallDistance(0f);
					player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, 100, 4, false, false));
				});
		}
	}

	/** Arena mode: generate the arena around 0,0 in the background right after launch, ready long before it is used. */
	private void pregenerateArena(World target) {
		if (settings.shrinkAfter <= 0 || !settings.shrinkTeleport) {
			return;
		}
		int radius = Math.max(settings.shrinkSize, settings.arenaSize) / 2 + 16;
		long started = System.currentTimeMillis();
		manager.pregen().generateArea(target, 0, 0, radius, 600).whenComplete((ignored, error) ->
			manager.logger().info("Room " + id + ": arena (radius " + radius + ") "
				+ (error == null ? "pre-generated in " + (System.currentTimeMillis() - started) / 1000 + "s." : "not fully pre-generated (" + error.getClass().getSimpleName() + ").")));
	}

	private void tickPlaying() {
		tickShrink();
		long left = secondsUntilSwap();
		if (left <= 0) {
			swap();
			updateSwapBar();
			return;
		}
		updateSwapBar();

		if (left <= settings.swapWarning) {
			String color = left <= 3 ? DeathSwapText.ACCENT_DANGER : DeathSwapText.ACCENT_GOLD;
			float pitch = 0.8f + (float) (settings.swapWarning - left) * 0.08f;
			for (Player player : world.getPlayers()) {
				DeathSwapText.title(player, DeathSwapText.color(color, "<b>" + left + "</b>"), "<white>Hoán đổi vị trí sắp diễn ra!</white>", 1100);
				player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, pitch);
			}
		}
	}

	// ------------------------------------------------------------------
	// building the round

	private void prepare() {
		List<Player> players = eligiblePlayers();
		if (players.isEmpty()) {
			state = State.WAITING;
			broadcast(DeathSwapText.ERROR + "Không có người chơi để bắt đầu ván đấu.");
			return;
		}

		state = State.PREPARING;
		int token = ++roundToken;
		participants.clear();
		alive.clear();
		credits.clear();
		roundTraps.clear();
		deathSpots.clear();
		lifeHealth.clear();
		keepInventory.clear();
		lobbyBound.clear();
		winner = null;
		swaps = 0;
		startedAt = 0;
		finishedAt = 0;
		for (Player player : players) {
			participants.add(player.getUniqueId());
			names.put(player.getUniqueId(), player.getName());
		}

		boolean pooled = settings.seed == 0L && manager.worlds().status().ready() > 0;
		preparingStage = pooled ? "Đang nạp thế giới" : "Đang tạo thế giới";
		prep = Prep.WORLD;
		prepProgress = 0D;
		prepWeights = pooled ? WEIGHTS_POOLED : WEIGHTS_GENERATING;
		prepareStartedAt = System.currentTimeMillis();
		broadcast(DeathSwapText.WAIT + (pooled
			? "Đang chuẩn bị thế giới cho ván đấu..."
			: "Đang tạo thế giới mới, khoảng " + DeathSwapText.white(DeathSwapText.clock(manager.worlds().expectedGenerationSeconds())) + "..."));
		manager.refreshRoom(this);

		Bukkit.getScheduler().runTask(manager.plugin(), () -> manager.worlds().acquire(settings.seed).whenComplete((acquired, error) -> {
			if (token != roundToken || state != State.PREPARING) {
				if (acquired != null) {
					manager.worlds().release(acquired);
				}
				return;
			}
			if (acquired == null) {
				if (error != null) {
					manager.logger().error("Room " + id + " could not get a world.", error);
				}
				broadcast(DeathSwapText.ERROR + "Không thể tạo thế giới cho ván đấu. Thử lại sau nhé.");
				state = State.WAITING;
				participants.clear();
				manager.refreshRoom(this);
				return;
			}
			spread(acquired);
		}));
	}

	private void spread(World acquired) {
		world = acquired;
		manager.indexWorld(this, acquired);
		acquired.setDifficulty(settings.difficulty);
		acquired.setTime(0L);
		acquired.setStorm(false);
		WorldBorder border = acquired.getWorldBorder();
		border.setCenter(0, 0);
		border.setSize(settings.worldBorder);
		// only matters once it shrinks: standing outside then really hurts
		border.setDamageBuffer(0D);
		border.setDamageAmount(1.0D);
		border.setWarningDistance(24);
		shrinkStarted = false;
		finalCloseStarted = false;
		// gamerules are per world on 26.x; the console takes the live (snake_case) name
		try {
			Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
				"execute in " + acquired.getKey().asString() + " run gamerule locator_bar " + settings.locatorBar);
		} catch (RuntimeException exception) {
			manager.logger().warn("Could not set locator_bar in " + acquired.getName() + ": " + exception.getMessage());
		}

		prep = Prep.SPOTS;
		prepProgress = 0D;
		preparingStage = "Đang tìm điểm xuất phát";
		manager.refreshRoom(this);

		List<UUID> order = new ArrayList<>(participants);
		Collections.shuffle(order);
		int count = order.size();
		double base = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
		int radius = Math.max(32, Math.min(settings.spreadRadius, settings.worldBorder / 2 - 64));

		int token = roundToken;
		java.util.concurrent.atomic.AtomicInteger found = new java.util.concurrent.atomic.AtomicInteger();
		Map<UUID, CompletableFuture<Location>> spots = new LinkedHashMap<>();
		for (int index = 0; index < count; index++) {
			double angle = base + (Math.PI * 2 * index) / count;
			int x = (int) Math.round(Math.cos(angle) * radius);
			int z = (int) Math.round(Math.sin(angle) * radius);
			CompletableFuture<Location> spot = findSpot(acquired, x, z, 0);
			spot.whenComplete((location, error) -> prepProgress = (double) found.incrementAndGet() / count);
			spots.put(order.get(index), spot);
		}

		CompletableFuture.allOf(spots.values().toArray(new CompletableFuture[0])).whenComplete((ignored, error) ->
			Bukkit.getScheduler().runTask(manager.plugin(), () -> {
				if (token != roundToken || state != State.PREPARING || world != acquired) {
					return;
				}
				if (error != null) {
					manager.logger().error("Room " + id + " could not find spawn spots.", error);
				}
				pregenerate(acquired, spots, token);
			}));
	}

	/**
	 * Generate the terrain around every spawn point, one after another (Chunky
	 * runs one task per world), and only then send anyone in, so nobody lands
	 * in half-loaded chunks. A spawn area that fails or times out is skipped,
	 * never blocking the round.
	 */
	private void pregenerate(World acquired, Map<UUID, CompletableFuture<Location>> spots, int token) {
		List<Location> targets = new ArrayList<>();
		for (CompletableFuture<Location> spot : spots.values()) {
			Location location = spot.getNow(null);
			if (location != null) {
				targets.add(location);
			}
		}

		prep = Prep.PREGEN;
		prepProgress = 0D;
		int total = targets.size();
		long started = System.currentTimeMillis();
		// each step starts on the main thread, whichever thread finished the last one
		java.util.concurrent.Executor mainThread = task -> Bukkit.getScheduler().runTask(manager.plugin(), task);
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (int index = 0; index < total; index++) {
			int position = index;
			Location target = targets.get(index);
			chain = chain.thenComposeAsync(ignored -> {
				if (token != roundToken) {
					return CompletableFuture.completedFuture(null);
				}
				preparingStage = total > 1
					? "Đang tạo vùng xuất phát " + (position + 1) + "/" + total
					: "Đang tạo vùng xuất phát";
				prepProgress = (double) position / total;
				return manager.pregen().generate(acquired, target.getBlockX(), target.getBlockZ(),
						inside -> prepProgress = (position + Math.max(0D, Math.min(1D, inside))) / total)
					.handle((done, error) -> {
						if (error != null) {
							manager.logger().warn("Room " + id + ": spawn area " + (position + 1) + "/" + total
								+ " was not fully generated (" + error.getClass().getSimpleName() + "), starting anyway.");
						}
						return null;
					});
			}, mainThread);
		}

		chain.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(manager.plugin(), () -> {
			if (token != roundToken || state != State.PREPARING || world != acquired) {
				return;
			}
			prepProgress = 1D;
			manager.logger().info("Room " + id + ": " + total + " spawn area(s) ready in "
				+ String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - started) / 1000D) + "s ("
				+ (manager.pregen().usesChunky() ? "Chunky" : "direct") + ", radius " + manager.pregen().options().radius() + ").");
			holdSpawnChunks(acquired, targets);
			preparedSpots = spots;
			beginCountdown(pendingCountdown);
		}));
	}

	/** A participant who will still be sent in when the countdown ends. */
	private boolean stillPlaying(UUID playerId) {
		return members.contains(playerId) && !sittingOut.contains(playerId) && Bukkit.getPlayer(playerId) != null;
	}

	/** Set the max-health attribute (half-hearts); optionally fill health up to it. */
	static void setMaxHealth(Player player, double value, boolean fill) {
		org.bukkit.attribute.AttributeInstance attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
		if (attribute == null) {
			return;
		}
		attribute.setBaseValue(Math.max(1D, value));
		if (player.isDead()) {
			return;
		}
		double max = attribute.getValue();
		if (fill || player.getHealth() > max) {
			player.setHealth(max);
		}
	}

	/** The starting kit (kit setting). */
	private void giveKit(Player player) {
		List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
		switch (settings.kit) {
			case "tools" -> {
				items.add(new org.bukkit.inventory.ItemStack(Material.STONE_PICKAXE));
				items.add(new org.bukkit.inventory.ItemStack(Material.STONE_AXE));
				items.add(new org.bukkit.inventory.ItemStack(Material.STONE_SHOVEL));
				items.add(new org.bukkit.inventory.ItemStack(Material.WATER_BUCKET));
				items.add(new org.bukkit.inventory.ItemStack(Material.BREAD, 16));
			}
			case "basic" -> {
				items.add(new org.bukkit.inventory.ItemStack(Material.WATER_BUCKET));
				items.add(new org.bukkit.inventory.ItemStack(Material.BREAD, 16));
			}
			case "food" -> items.add(new org.bukkit.inventory.ItemStack(Material.BREAD, 16));
			default -> {
			}
		}
		if (!items.isEmpty()) {
			player.getInventory().addItem(items.toArray(new org.bukkit.inventory.ItemStack[0]));
		}
	}

	/** Revoke every advancement criterion the player has (the same as /advancement revoke <p> everything, without chat spam). */
	private static void resetAdvancements(Player player) {
		java.util.Iterator<org.bukkit.advancement.Advancement> iterator = Bukkit.advancementIterator();
		while (iterator.hasNext()) {
			org.bukkit.advancement.AdvancementProgress progress = player.getAdvancementProgress(iterator.next());
			for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
				progress.revokeCriteria(criterion);
			}
		}
	}

	/** Keep the chunks right around each spawn loaded until players have arrived. */
	private void holdSpawnChunks(World target, List<Location> spawns) {
		List<long[]> held = new ArrayList<>();
		for (Location spawn : spawns) {
			int chunkX = spawn.getBlockX() >> 4;
			int chunkZ = spawn.getBlockZ() >> 4;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (target.addPluginChunkTicket(chunkX + dx, chunkZ + dz, manager.plugin())) {
						held.add(new long[] {chunkX + dx, chunkZ + dz});
					}
				}
			}
		}
		Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
			if (Bukkit.getWorld(target.getUID()) == null) {
				return;
			}
			for (long[] chunk : held) {
				target.removePluginChunkTicket((int) chunk[0], (int) chunk[1], manager.plugin());
			}
		}, 20L * (pendingCountdown + 20));
	}

	private static final int SPOT_ATTEMPTS = 32;
	/** From this attempt on, any safe ground will do even in a poor biome. */
	private static final int SPOT_RELAX_AT = 22;

	private CompletableFuture<Location> findSpot(World target, int x, int z, int attempt) {
		return findSpot(target, x, z, x, z, attempt);
	}

	/**
	 * A spawn point near (originX, originZ): solid, dry ground in a biome with
	 * wood and food. Each miss tries a random point a bit further from the
	 * origin (not from the last miss, so spawns stay spread around the ring),
	 * inside the world border. Late attempts accept any safe ground; the last
	 * one takes whatever is there.
	 */
	private CompletableFuture<Location> findSpot(World target, int originX, int originZ, int x, int z, int attempt) {
		return target.getChunkAtAsync(x >> 4, z >> 4, true).thenCompose(chunk -> {
			Block top = target.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
			boolean safe = isSafeGround(top);
			boolean goodBiome = isGoodSpawnBiome(top);
			if ((safe && (goodBiome || attempt >= SPOT_RELAX_AT)) || attempt >= SPOT_ATTEMPTS) {
				if (attempt > 0) {
					manager.logger().info("Room " + id + ": spawn at " + x + ", " + z + " after " + (attempt + 1) + " tries ("
						+ biomeName(top) + (goodBiome ? "" : ", fallback") + ").");
				}
				return CompletableFuture.completedFuture(new Location(target, x + 0.5, top.getY() + 1, z + 0.5,
					ThreadLocalRandom.current().nextFloat() * 360f - 180f, 0f));
			}
			double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
			int distance = 48 * (attempt + 1);
			int limit = Math.max(64, settings.worldBorder / 2 - 64);
			int nextX = Math.max(-limit, Math.min(limit, originX + (int) (Math.cos(angle) * distance)));
			int nextZ = Math.max(-limit, Math.min(limit, originZ + (int) (Math.sin(angle) * distance)));
			return findSpot(target, originX, originZ, nextX, nextZ, attempt + 1);
		});
	}

	private static String biomeName(Block block) {
		try {
			return block.getBiome().key().value();
		} catch (RuntimeException exception) {
			return "?";
		}
	}

	/**
	 * Biomes where a fresh start is miserable: open water, deserts with no wood,
	 * frozen and snowy land, bare peaks and the mushroom island.
	 */
	private static boolean isGoodSpawnBiome(Block block) {
		String name = biomeName(block);
		return !(name.contains("ocean")
			|| name.contains("river")
			|| name.contains("beach")
			|| name.contains("desert")
			|| name.contains("badlands")
			|| name.contains("frozen")
			|| name.contains("snowy")
			|| name.contains("ice")
			|| name.contains("grove")
			|| name.contains("peaks")
			|| name.contains("shore")
			|| name.contains("mushroom"));
	}

	private static boolean isSafeGround(Block block) {
		if (block.isLiquid()) {
			return false;
		}
		Material type = block.getType();
		return type != Material.MAGMA_BLOCK
			&& type != Material.CACTUS
			&& type != Material.POWDER_SNOW
			&& type != Material.CAMPFIRE
			&& type != Material.SOUL_CAMPFIRE
			&& type != Material.SWEET_BERRY_BUSH
			&& type != Material.POINTED_DRIPSTONE
			&& !type.isAir();
	}

	private void launch(World target, Map<UUID, CompletableFuture<Location>> spots, Throwable error) {
		if (state != State.STARTING || world != target) {
			return;
		}
		if (error != null) {
			manager.logger().error("Room " + id + " could not find spawn spots.", error);
		}

		for (Map.Entry<UUID, CompletableFuture<Location>> entry : spots.entrySet()) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null || !stillPlaying(entry.getKey())) {
				participants.remove(entry.getKey());
				continue;
			}
			Location spot = entry.getValue().getNow(null);
			if (spot == null) {
				spot = target.getSpawnLocation();
			}
			player.closeInventory();
			LobbyService.resetPlayer(player, GameMode.SURVIVAL);
			// also at the start, for anyone who left mid-round and missed the reset at the end
			resetAdvancements(player);
			double health = settings.maxHearts * 2D;
			lifeHealth.put(player.getUniqueId(), health);
			setMaxHealth(player, health, true);
			giveKit(player);
			player.setRespawnLocation(null, true);
			player.teleportAsync(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
			alive.add(player.getUniqueId());
			manager.stats().get(player.getUniqueId()).name = player.getName();
		}

		if (alive.isEmpty()) {
			broadcast(DeathSwapText.ERROR + "Không còn ai để bắt đầu ván đấu.");
			endRound();
			return;
		}

		swapOrder.clear();
		swapOrder.addAll(alive);
		Collections.shuffle(swapOrder);
		// the spawn-area Chunky task is done by now; the arena gets the generator next
		Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
			if (world == target && state == State.PLAYING) {
				pregenerateArena(target);
			}
		}, 20L * 5);

		state = State.PLAYING;
		rounds++;
		startedAt = System.currentTimeMillis();
		scheduleNextSwap();
		int firstSwap = scheduleFirstSwap();
		updateSwapBar();

		String firstLine;
		if (firstSwap <= 0) {
			firstLine = "Chuẩn bị đồ thật nhanh nhé!";
		} else {
			boolean hidden = settings.hideFirstSwap || !settings.showTimer;
			int low = settings.firstSwapMin;
			int high = Math.max(settings.firstSwapMin, settings.firstSwapMax);
			String when = !hidden
				? DeathSwapText.clock(firstSwap)
				: low == high ? "??? (khoảng " + DeathSwapText.clock(low) + ")"
				: "??? (ngẫu nhiên " + DeathSwapText.clock(low) + "–" + DeathSwapText.clock(high) + ")";
			firstLine = "Lần hoán đổi đầu tiên sau " + DeathSwapText.color(DeathSwapText.ACCENT_GOLD, when) + ", tranh thủ chuẩn bị!";
		}

		broadcastBlock(
			DeathSwapText.blockHeader("HƯỚNG DẪN NHANH"),
			DeathSwapText.blockLine("Cứ mỗi " + DeathSwapText.color(DeathSwapText.ACCENT_GOLD, intervalText()) + ", mọi người sẽ bị hoán đổi vị trí."),
			DeathSwapText.blockLine(firstLine),
			DeathSwapText.blockLine("Gài bẫy ngay chỗ mình đứng để người khác dính đòn."),
			DeathSwapText.blockLine("Người cuối cùng còn sống sẽ " + DeathSwapText.color(DeathSwapText.ACCENT_GOLD, "<b>chiến thắng</b>") + "!")
		);
		for (Player player : audience()) {
			if (alive.contains(player.getUniqueId())) {
				DeathSwapText.title(player, DeathSwapText.BRAND, "<white>Sống sót đến cuối cùng!</white>", 3000);
				player.playSound(player, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
			} else {
				DeathSwapText.send(player, DeathSwapText.INFO + "Ván đấu đã bắt đầu. Xem bằng " + DeathSwapText.command("/ds watch") + ".");
			}
		}

		manager.logger().success("Room " + id + ": round started in " + target.getName() + " with " + alive.size() + " players.");
		manager.refreshRoom(this);
	}

	private String intervalText() {
		if (settings.swapMin == settings.swapMax) {
			return DeathSwapText.clock(settings.swapMin);
		}
		return DeathSwapText.clock(settings.swapMin) + "–" + DeathSwapText.clock(settings.swapMax);
	}

	/**
	 * Replace the first swap's time with a random one in first-swap-min..first-swap-max
	 * (call right after scheduleNextSwap at launch). Returns the seconds chosen, or 0
	 * when the room uses the normal interval for its first swap too.
	 */
	private int scheduleFirstSwap() {
		int low = settings.firstSwapMin;
		if (low <= 0) {
			return 0;
		}
		int high = Math.max(low, settings.firstSwapMax);
		int seconds = low == high ? low : ThreadLocalRandom.current().nextInt(low, high + 1);
		swapInterval = seconds;
		nextSwapAt = startedAt + seconds * 1000L;
		manager.logger().info("Room " + id + ": first swap in " + seconds + "s (range " + low + "–" + high + "s).");
		return seconds;
	}

	private void scheduleNextSwap() {
		int seconds = settings.swapMin >= settings.swapMax
			? settings.swapMin
			: ThreadLocalRandom.current().nextInt(settings.swapMin, settings.swapMax + 1);
		swapInterval = Math.max(1, seconds);
		nextSwapAt = System.currentTimeMillis() + seconds * 1000L;
	}

	/** The countdown bar for everyone in the round's world; follows show-timer (??? while hidden). */
	private void updateSwapBar() {
		if (!settings.swapBossbar || state != State.PLAYING || world == null) {
			hideSwapBar();
			return;
		}
		long left = secondsUntilSwap();
		boolean visible = swapTimerVisible();
		boolean warning = left <= settings.swapWarning;
		String text = !visible
			? "<gray>↔ Hoán đổi tiếp theo:</gray> <white><b>" + hiddenSwapLabel() + "</b></white>"
			: DeathSwapText.color(warning ? DeathSwapText.ACCENT_DANGER : DeathSwapText.ACCENT_BLUE, "↔ Hoán đổi sau <b>" + DeathSwapText.clock(left) + "</b>");
		float progress = visible ? Math.max(0f, Math.min(1f, (float) left / swapInterval)) : 1f;
		BossBar.Color color = warning ? BossBar.Color.RED : (visible ? BossBar.Color.BLUE : BossBar.Color.PURPLE);
		if (swapBar == null) {
			swapBar = BossBar.bossBar(DeathSwapText.mm(text), progress, color, BossBar.Overlay.PROGRESS);
		} else {
			swapBar.name(DeathSwapText.mm(text));
			swapBar.progress(progress);
			swapBar.color(color);
		}

		Set<UUID> now = new HashSet<>();
		for (Player player : world.getPlayers()) {
			now.add(player.getUniqueId());
			if (swapBarViewers.add(player.getUniqueId())) {
				player.showBossBar(swapBar);
			}
		}
		for (UUID playerId : new ArrayList<>(swapBarViewers)) {
			if (!now.contains(playerId)) {
				swapBarViewers.remove(playerId);
				Player player = Bukkit.getPlayer(playerId);
				if (player != null) {
					player.hideBossBar(swapBar);
				}
			}
		}
	}

	private void hideSwapBar() {
		if (swapBar != null) {
			for (UUID playerId : swapBarViewers) {
				Player player = Bukkit.getPlayer(playerId);
				if (player != null) {
					player.hideBossBar(swapBar);
				}
			}
		}
		swapBarViewers.clear();
	}

	// ------------------------------------------------------------------
	// the swap

	private void swap() {
		List<Player> players = new ArrayList<>();
		for (UUID playerId : alive) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && !player.isDead() && player.getWorld().equals(world)) {
				players.add(player);
			}
		}

		scheduleNextSwap();
		if (players.size() < 2) {
			return;
		}

		// who lands where: sources[i] is the player whose spot player i takes.
		// Every pattern is a derangement, so nobody ever stays put.
		int[] sources = swapSources(players);
		int count = players.size();
		Location[] spots = new Location[count];
		float[] falls = new float[count];
		Vector[] velocities = new Vector[count];
		for (int index = 0; index < count; index++) {
			Player player = players.get(index);
			if (player.isInsideVehicle()) {
				player.leaveVehicle();
			}
			player.eject();
			spots[index] = player.getLocation().clone();
			falls[index] = player.getFallDistance();
			velocities[index] = player.getVelocity().clone();
		}

		long now = System.currentTimeMillis();
		swaps++;
		for (int index = 0; index < count; index++) {
			Player player = players.get(index);
			int from = sources[index];
			Player previous = players.get(from);

			player.teleport(spots[from], PlayerTeleportEvent.TeleportCause.PLUGIN);
			if (settings.keepMomentum) {
				player.setFallDistance(falls[from]);
				player.setVelocity(velocities[from]);
			}

			credits.put(player.getUniqueId(), new TrapCredit(previous.getUniqueId(), now));
			player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
			DeathSwapText.title(player,
				DeathSwapText.color(DeathSwapText.ACCENT_BLUE, "<b>↔ HOÁN ĐỔI!</b>"),
				"<gray>Bạn đang ở chỗ của</gray> " + DeathSwapText.white(previous.getName()),
				1800);
		}

		broadcast(DeathSwapText.color(DeathSwapText.ACCENT_BLUE, "↔") + " Hoán đổi lần " + DeathSwapText.white("#" + swaps)
			+ "! <gray>Còn</gray> " + DeathSwapText.white(String.valueOf(alive.size())) + " <gray>người sống sót.</gray>");
		drainHearts();
	}

	/**
	 * swap-drain: every swap costs each survivor that many max hearts, so a
	 * round has a clock even with LifeSwap. Whoever reaches 0 is out (several
	 * at once can end the round in a draw).
	 */
	private void drainHearts() {
		if (settings.swapDrain <= 0 || state != State.PLAYING) {
			return;
		}
		double drain = settings.swapDrain * 2D;
		List<String> out = new ArrayList<>();
		for (UUID playerId : new ArrayList<>(alive)) {
			Player player = Bukkit.getPlayer(playerId);
			double left = lifeHealth.getOrDefault(playerId, settings.maxHearts * 2D) - drain;
			if (left > 0D) {
				lifeHealth.put(playerId, left);
				if (player != null && !player.isDead()) {
					setMaxHealth(player, left, false);
					DeathSwapText.send(player, DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "-" + settings.swapDrain + "❤")
						+ " <gray>sau lần hoán đổi, còn</gray> " + DeathSwapText.white(hearts(left) + " tim"));
				}
				continue;
			}
			lifeHealth.put(playerId, 0D);
			eliminate(playerId);
			out.add(names.getOrDefault(playerId, "?"));
			if (player != null && player.isOnline()) {
				if (player.isDead()) {
					player.spigot().respawn();
				}
				deathSpots.put(playerId, player.getLocation().clone());
				player.getWorld().strikeLightningEffect(player.getLocation());
				LobbyService.resetPlayer(player, GameMode.SPECTATOR);
				DeathSwapText.title(player, "<red><b>☠ HẾT TIM</b></red>", "<gray>Bạn đang theo dõi ván đấu</gray>", 2500);
			}
		}
		if (!out.isEmpty()) {
			broadcast("<red>☠</red> " + DeathSwapText.white(String.join(", ", out))
				+ " <gray>hết tim sau lần hoán đổi.</gray>");
			Bukkit.getScheduler().runTaskLater(manager.plugin(), this::checkWin, 2L);
		}
	}

	/**
	 * The swap pattern (swap-pattern), reordering {@code players} in place:
	 * shuffle: a new random ring every swap (everyone takes the next one's spot);
	 * random: any random derangement, so groups and pairs can form;
	 * ring: the ring fixed at the start of the round, dead players skipped;
	 * pairs: fixed partners from that order, an odd one out makes a ring of three.
	 */
	private int[] swapSources(List<Player> players) {
		int count = players.size();
		int[] sources = new int[count];
		switch (settings.swapPattern) {
			case "random" -> {
				Collections.shuffle(players);
				List<Integer> order = new ArrayList<>();
				for (int index = 0; index < count; index++) {
					order.add(index);
				}
				boolean deranged;
				do {
					Collections.shuffle(order);
					deranged = true;
					for (int index = 0; index < count; index++) {
						if (order.get(index) == index) {
							deranged = false;
							break;
						}
					}
				} while (!deranged);
				for (int index = 0; index < count; index++) {
					sources[index] = order.get(index);
				}
				return sources;
			}
			case "ring", "pairs" -> players.sort(Comparator.comparingInt(player -> {
				int position = swapOrder.indexOf(player.getUniqueId());
				return position < 0 ? Integer.MAX_VALUE : position;
			}));
			default -> Collections.shuffle(players);
		}

		if ("pairs".equals(settings.swapPattern) && count >= 2) {
			int paired = count % 2 == 0 ? count : count - 3;
			for (int index = 0; index < paired; index += 2) {
				sources[index] = index + 1;
				sources[index + 1] = index;
			}
			if (paired < count) {
				// the last three rotate
				sources[count - 3] = count - 2;
				sources[count - 2] = count - 1;
				sources[count - 1] = count - 3;
			}
			return sources;
		}
		for (int index = 0; index < count; index++) {
			sources[index] = (index + 1) % count;
		}
		return sources;
	}

	// ------------------------------------------------------------------
	// deaths, quits, respawns and the end

	/** Returns the death line to show to the room, or null to leave the vanilla one. */
	String handleDeath(Player player, String vanillaPlain) {
		UUID playerId = player.getUniqueId();
		if (state != State.PLAYING || !alive.contains(playerId)) {
			return null;
		}

		String trapper = null;
		UUID trapperId = null;
		TrapCredit credit = credits.get(playerId);
		if (credit != null && settings.trapCredit > 0
			&& System.currentTimeMillis() - credit.at() <= settings.trapCredit * 1000L
			&& !credit.by().equals(playerId)) {
			trapperId = credit.by();
			trapper = names.getOrDefault(trapperId, "?");
			roundTraps.merge(trapperId, 1, Integer::sum);
			manager.stats().get(trapperId).traps++;
		}

		deathSpots.put(playerId, player.getLocation().clone());
		// a lightning bolt where they fell: visual and sound only, no damage or fire
		player.getWorld().strikeLightningEffect(player.getLocation());

		String lifeNote = "";
		boolean survives = false;
		boolean suddenDeath = settings.shrinkSuddenDeath && shrinkStarted;
		if (settings.lifeswap && suddenDeath) {
			lifeNote = DeathSwapText.DOT + DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "đấu trường: chết là bị loại!");
		}
		if (settings.lifeswap && !suddenDeath) {
			double steal = settings.lifeswapSteal * 2D;
			double left = lifeHealth.getOrDefault(playerId, settings.maxHearts * 2D) - steal;
			if (trapperId != null && alive.contains(trapperId)) {
				double gained = Math.min(100D, lifeHealth.getOrDefault(trapperId, settings.maxHearts * 2D) + steal);
				lifeHealth.put(trapperId, gained);
				Player thief = Bukkit.getPlayer(trapperId);
				if (thief != null && !thief.isDead()) {
					setMaxHealth(thief, gained, false);
					thief.setHealth(Math.min(thief.getHealth() + steal, gained));
					DeathSwapText.send(thief, DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "❤")
						+ " Bạn cướp được " + DeathSwapText.white(settings.lifeswapSteal + " tim") + " từ " + DeathSwapText.white(player.getName())
						+ "! <gray>(" + hearts(gained) + " tim)</gray>");
					thief.playSound(thief, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
				}
			}
			if (left > 0D) {
				survives = true;
				lifeHealth.put(playerId, left);
				keepInventory.add(playerId);
				manager.stats().get(playerId).deaths++;
				lifeNote = DeathSwapText.DOT + DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "-" + settings.lifeswapSteal + "❤")
					+ " <gray>còn " + hearts(left) + " tim</gray>";
			} else {
				lifeHealth.put(playerId, 0D);
				lifeNote = DeathSwapText.DOT + DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "hết tim!");
			}
		}
		if (!survives) {
			eliminate(playerId);
		}

		String reason = vanillaPlain == null ? "" : vanillaPlain;
		if (reason.startsWith(player.getName())) {
			reason = reason.substring(player.getName().length()).trim();
		}

		String line = "<red>☠</red> " + manager.playerDisplay(player) + " <gray>" + DeathSwapText.escape(reason) + "</gray>";
		if (trapper != null) {
			line += DeathSwapText.DOT + DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "bẫy của") + " " + DeathSwapText.white(trapper);
		}
		line += lifeNote;

		Bukkit.getScheduler().runTask(manager.plugin(), () -> {
			if (player.isOnline() && player.isDead()) {
				player.spigot().respawn();
			}
		});
		Bukkit.getScheduler().runTaskLater(manager.plugin(), this::checkWin, 2L);
		return line;
	}

	/** Dead players watch from where they died; anyone else is sent to the lobby. */
	Location respawnLocation(Player player) {
		UUID playerId = player.getUniqueId();
		if (world != null && state == State.PLAYING && alive.contains(playerId)) {
			// LifeSwap / revive: back at whichever start point (all generated before
			// the round) is farthest from every other survivor, so nobody can camp a
			// known respawn and you do not land next to whoever was swapped there
			Location spot = farthestStartPoint(playerId);
			spot = spot != null ? spot : world.getSpawnLocation();
			WorldBorder border = world.getWorldBorder();
			// while the arena closes, the border edge moves ~3 blocks/s: a spot just
			// inside it is outside again seconds later (players died to the border
			// right after respawning), so stay within half the current radius
			double safeHalf = shrinkStarted ? Math.max(8D, border.getSize() / 4D) : border.getSize() / 2D - 8D;
			if (Math.abs(spot.getX() - border.getCenter().getX()) > safeHalf
				|| Math.abs(spot.getZ() - border.getCenter().getZ()) > safeHalf) {
				double half = Math.max(4D, safeHalf);
				int x = (int) Math.max(-half, Math.min(half, spot.getX()));
				int z = (int) Math.max(-half, Math.min(half, spot.getZ()));
				Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
				spot = new Location(world, x + 0.5, top.getY() + 1, z + 0.5, spot.getYaw(), 0f);
			}
			return spot;
		}
		if (world != null && (state == State.PLAYING || state == State.FINISHED)
			&& participants.contains(playerId) && !alive.contains(playerId) && !lobbyBound.contains(playerId)) {
			Location spot = deathSpots.get(playerId);
			return spot != null && spot.getWorld() != null ? spot : spectateTarget();
		}
		return null;
	}

	/**
	 * Put an eliminated participant back into the running round: survival at
	 * their start point (or just inside a closing border) with full hearts,
	 * the starting kit and 5 s of protection. Null on success, else the reason.
	 */
	public String revive(Player player) {
		UUID playerId = player.getUniqueId();
		if (state != State.PLAYING || world == null) {
			return "Phòng chưa có ván đang diễn ra.";
		}
		if (alive.contains(playerId)) {
			return player.getName() + " vẫn đang sống.";
		}
		if (!members.contains(playerId)) {
			return player.getName() + " không ở trong phòng này.";
		}
		if (player.isDead()) {
			player.spigot().respawn();
		}

		participants.add(playerId);
		names.put(playerId, player.getName());
		alive.add(playerId);
		lobbyBound.remove(playerId);
		sittingOut.remove(playerId);
		if (!swapOrder.contains(playerId)) {
			swapOrder.add(playerId);
		}
		// eliminate() counted a finished game; the round goes on for them
		DeathSwapStats.Entry entry = manager.stats().get(playerId);
		entry.games = Math.max(0, entry.games - 1);

		Location spot = respawnLocation(player);
		player.setSpectatorTarget(null);
		LobbyService.resetPlayer(player, GameMode.SURVIVAL);
		double health = settings.maxHearts * 2D;
		lifeHealth.put(playerId, health);
		setMaxHealth(player, health, true);
		giveKit(player);
		player.setRespawnLocation(null, true);
		player.teleportAsync(spot != null ? spot : world.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN).thenRun(() -> {
			player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, 100, 4, false, false));
			player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, 100, 0, false, false));
		});
		DeathSwapText.title(player, DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "<b>✚ HỒI SINH</b>"),
			"<gray>Bạn được đưa trở lại ván đấu</gray>", 2500);
		broadcast(DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "✚") + " " + manager.playerDisplay(player)
			+ " <gray>đã được hồi sinh!</gray> <gray>Còn</gray> " + DeathSwapText.white(String.valueOf(alive.size())) + " <gray>người sống sót.</gray>");
		updateSwapBar();
		manager.refreshRoom(this);
		return null;
	}

	/** The round's start point with the largest distance to the nearest other living player. */
	private Location farthestStartPoint(UUID playerId) {
		List<Location> others = new ArrayList<>();
		for (UUID otherId : alive) {
			Player other = otherId.equals(playerId) ? null : Bukkit.getPlayer(otherId);
			if (other != null && other.getWorld().equals(world)) {
				others.add(other.getLocation());
			}
		}
		Location best = null;
		double bestDistance = -1D;
		for (CompletableFuture<Location> future : preparedSpots.values()) {
			Location start = future.getNow(null);
			if (start == null || !world.equals(start.getWorld())) {
				continue;
			}
			double nearest = Double.MAX_VALUE;
			for (Location other : others) {
				double dx = other.getX() - start.getX();
				double dz = other.getZ() - start.getZ();
				nearest = Math.min(nearest, dx * dx + dz * dz);
			}
			if (nearest > bestDistance) {
				bestDistance = nearest;
				best = start;
			}
		}
		return best == null ? null : best.clone();
	}

	/** LifeSwap: whether this death keeps the inventory (asked once by the death listener). */
	public boolean consumeKeepInventory(UUID playerId) {
		return keepInventory.remove(playerId);
	}

	private static String hearts(double halfHearts) {
		double value = halfHearts / 2D;
		return value == Math.floor(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
	}

	void handleRespawned(Player player) {
		UUID playerId = player.getUniqueId();
		if (world != null && state == State.PLAYING && player.getWorld().equals(world) && alive.contains(playerId)) {
			// LifeSwap: back in with fewer hearts, briefly protected
			double health = lifeHealth.getOrDefault(playerId, settings.maxHearts * 2D);
			player.setGameMode(GameMode.SURVIVAL);
			setMaxHealth(player, health, true);
			player.setFoodLevel(20);
			player.setFireTicks(0);
			player.setFallDistance(0f);
			player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, 100, 4, false, false));
			player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, 100, 0, false, false));
			DeathSwapText.title(player, DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "<b>-" + settings.lifeswapSteal + " ❤</b>"),
				"<gray>Còn</gray> " + DeathSwapText.white(hearts(health) + " tim") + " <gray>· bảo vệ 5 giây</gray>", 2500);
			return;
		}
		if (world != null && player.getWorld().equals(world) && !alive.contains(playerId)) {
			LobbyService.resetPlayer(player, GameMode.SPECTATOR);
			DeathSwapText.title(player, "<red><b>☠ BỊ LOẠI</b></red>", "<gray>Bạn đang theo dõi ván đấu</gray>", 2500);
			DeathSwapText.send(player, DeathSwapText.INFO + "Theo dõi người khác bằng " + DeathSwapText.command("/ds spec")
				+ ", về sảnh bằng " + DeathSwapText.command("/spawn") + ".");
		}
	}

	private void eliminate(UUID playerId) {
		if (!alive.remove(playerId)) {
			return;
		}

		DeathSwapStats.Entry entry = manager.stats().get(playerId);
		entry.games++;
		entry.deaths++;
		entry.longestSurviveSeconds = Math.max(entry.longestSurviveSeconds, elapsedSeconds());

		if (alive.size() > 1) {
			broadcast(DeathSwapText.INFO + "Còn lại " + DeathSwapText.white(String.valueOf(alive.size())) + " người chơi.");
		}
		for (Player player : audience()) {
			player.playSound(player, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.4f);
		}
	}

	private void checkWin() {
		if (state != State.PLAYING) {
			return;
		}
		boolean soloRound = participants.size() < 2;
		if (alive.isEmpty() || (!soloRound && alive.size() == 1)) {
			finish(alive.isEmpty() ? null : alive.iterator().next());
		}
	}

	private void finish(UUID winnerId) {
		state = State.FINISHED;
		finishedAt = System.currentTimeMillis();
		hideSwapBar();
		winner = winnerId;

		if (winnerId != null) {
			DeathSwapStats.Entry entry = manager.stats().get(winnerId);
			entry.games++;
			entry.wins++;
			entry.longestSurviveSeconds = Math.max(entry.longestSurviveSeconds, elapsedSeconds());
		}
		manager.stats().saveAsync();

		String winnerName = winnerName();
		Player winnerPlayer = winnerId == null ? null : Bukkit.getPlayer(winnerId);
		String winnerDisplay = winnerPlayer != null ? manager.playerDisplay(winnerPlayer) : DeathSwapText.white(winnerName == null ? "" : winnerName);

		List<String> block = new ArrayList<>();
		block.add(DeathSwapText.blockHeader("VÁN ĐẤU KẾT THÚC!"));
		block.add(winnerName != null
			? DeathSwapText.blockLine("Người chiến thắng: " + DeathSwapText.color(DeathSwapText.ACCENT_GOLD, "👑") + " " + winnerDisplay)
			: DeathSwapText.blockLine("Không ai sống sót. " + DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "Hòa!")));
		block.add(DeathSwapText.blockLine("Thời gian: " + DeathSwapText.color(DeathSwapText.ACCENT_TIME, "⌚ " + DeathSwapText.clock(elapsedSeconds()))
			+ DeathSwapText.DOT + "<white>Hoán đổi: </white>" + DeathSwapText.color(DeathSwapText.ACCENT_BLUE, "↔ " + swaps)));
		roundTraps.entrySet().stream()
			.max(Comparator.comparingInt(Map.Entry::getValue))
			.ifPresent(top -> block.add(DeathSwapText.blockLine("Bậc thầy gài bẫy: " + DeathSwapText.white(names.getOrDefault(top.getKey(), "?"))
				+ " <gray>(" + top.getValue() + " bẫy)</gray>")));
		block.add(DeathSwapText.blockLine("Tất cả sẽ được đưa về sảnh sau " + settings.endDelay + " giây"));
		broadcastBlock(block.toArray(new String[0]));

		for (Player player : audience()) {
			if (player.getUniqueId().equals(winnerId)) {
				DeathSwapText.title(player, DeathSwapText.color(DeathSwapText.ACCENT_GOLD, "<b>👑 CHIẾN THẮNG!</b>"), "<white>Bạn là người cuối cùng còn sống</white>", 5000);
				player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
			} else {
				DeathSwapText.title(player, "<white><b>KẾT THÚC</b></white>",
					winnerName != null ? winnerDisplay + " <gray>chiến thắng!</gray>" : "<gray>Không ai sống sót</gray>", 5000);
				player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
			}
		}

		if (winnerPlayer != null) {
			winnerPlayer.setInvulnerable(true);
			launchFireworks(winnerPlayer);
		}

		manager.logger().audit("Room " + id + ": round finished, winner=" + (winnerName == null ? "none" : winnerName)
			+ ", swaps=" + swaps + ", duration=" + elapsedSeconds() + "s.");
		int token = roundToken;
		Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
			if (state == State.FINISHED && token == roundToken) {
				endRound();
			}
		}, settings.endDelay * 20L);
	}

	private static final FireworkEffect.Type[] FIREWORK_SHAPES = {
		FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST, FireworkEffect.Type.BALL
	};
	private static final Color[] FIREWORK_COLORS = {
		Color.fromRGB(0xFF6B6B), Color.fromRGB(0xFFB86C), Color.fromRGB(0xFFD166), Color.fromRGB(0x7FD36B),
		Color.fromRGB(0x60A5FA), Color.fromRGB(0xC084FC), Color.fromRGB(0xFF7AB6)
	};

	/**
	 * A firework show around the winner for the whole end screen: a volley of
	 * 2–3 rockets every ~0.75 s in random shapes and the brand colours, rising
	 * a little higher each time.
	 */
	private void launchFireworks(Player player) {
		int volleys = Math.max(4, (settings.endDelay * 20 - 20) / 15);
		int token = roundToken;
		for (int index = 0; index < volleys; index++) {
			int volley = index;
			Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
				if (token != roundToken || state != State.FINISHED || !player.isOnline()
					|| world == null || !player.getWorld().equals(world)) {
					return;
				}
				ThreadLocalRandom random = ThreadLocalRandom.current();
				int rockets = 2 + random.nextInt(2);
				for (int rocket = 0; rocket < rockets; rocket++) {
					Location at = player.getLocation().add(random.nextDouble(-4, 4), 1, random.nextDouble(-4, 4));
					player.getWorld().spawn(at, Firework.class, firework -> {
						FireworkMeta meta = firework.getFireworkMeta();
						meta.addEffect(FireworkEffect.builder()
							.with(FIREWORK_SHAPES[random.nextInt(FIREWORK_SHAPES.length)])
							.withColor(FIREWORK_COLORS[random.nextInt(FIREWORK_COLORS.length)],
								FIREWORK_COLORS[random.nextInt(FIREWORK_COLORS.length)])
							.withFade(Color.WHITE)
							.trail(random.nextBoolean())
							.flicker(random.nextBoolean())
							.build());
						meta.setPower(volley < 3 ? 1 : 1 + random.nextInt(2));
						firework.setFireworkMeta(meta);
					});
				}
			}, index * 15L);
		}
	}

	/** Everyone back to the lobby, the world handed back, ready for the next round. */
	void endRound() {
		roundToken++;
		hideSwapBar();
		// every game starts from zero: advancements earned in a round do not carry over
		for (UUID playerId : participants) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null) {
				resetAdvancements(player);
			}
		}
		World ended = world;
		if (ended != null) {
			for (Player player : new ArrayList<>(ended.getPlayers())) {
				manager.sendToLobby(player);
			}
		}
		for (UUID playerId : members) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && (ended == null || player.getWorld().equals(ended))) {
				manager.sendToLobby(player);
			}
		}
		world = null;
		if (ended != null) {
			manager.pregen().cancel(ended);
			manager.unindexWorld(ended);
			manager.worlds().release(ended);
		}

		state = State.WAITING;
		forcedStart = false;
		preparedSpots = new LinkedHashMap<>();
		participants.clear();
		alive.clear();
		credits.clear();
		deathSpots.clear();
		lifeHealth.clear();
		keepInventory.clear();
		lobbyBound.clear();
		startedAt = 0;
		finishedAt = 0;
		manager.refreshRoom(this);
	}

	private Location spectateTarget() {
		for (UUID playerId : alive) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null && player.getWorld().equals(world)) {
				return player.getLocation().add(0, 3, 0);
			}
		}
		return world.getSpawnLocation();
	}

	// ------------------------------------------------------------------
	// display

	public String stateDisplay() {
		return switch (state) {
			case WAITING -> "<gray>Đang chờ</gray>";
			case STARTING -> "<yellow>Đếm ngược</yellow>";
			case PREPARING -> "<#ffd166>Chuẩn bị</#ffd166>";
			case PLAYING -> "<#36a8ff>Đang chơi</#36a8ff>";
			case FINISHED -> "<#ff6b6b>Kết thúc</#ff6b6b>";
		};
	}

	public String stateKey() {
		return state.name().toLowerCase(Locale.ROOT);
	}

	public String swapText() {
		if (state != State.PLAYING) {
			return "--:--";
		}
		return swapTimerVisible() ? DeathSwapText.clock(secondsUntilSwap()) : hiddenSwapLabel();
	}

	/** What a hidden timer shows: ??? or, with vague-timer, only which side of a minute it is. */
	private String hiddenSwapLabel() {
		if (!settings.vagueTimer) {
			return "???";
		}
		return secondsUntilSwap() > 60 ? "> 1 phút" : "< 1 phút";
	}

	/** The sidebar layout this player should see while in this room. */
	String templateKey(Player player) {
		return switch (state) {
			case WAITING -> "lobby";
			case STARTING -> "countdown";
			case PREPARING -> "preparing";
			case PLAYING -> alive.contains(player.getUniqueId()) ? "playing" : (isWatching(player) ? "spectating" : "lobby_playing");
			case FINISHED -> "finished";
		};
	}

	void fillPlaceholders(Player player, Map<String, String> values) {
		UUID playerId = player.getUniqueId();
		int eligible = eligiblePlayers().size();

		values.put("room_name", DeathSwapText.escape(displayName()));
		values.put("room_id", DeathSwapText.escape(id));
		values.put("room_chip", "<gradient:#7ED6FF:#A78BFA>⌂ " + DeathSwapText.escape(displayName()) + "</gradient>");
		values.put("room_members_chip", DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "👤") + " <white>" + members.size() + "</white><gray>/" + settings.maxPlayers + "</gray>");
		values.put("state_display", stateDisplay());
		values.put("status_chip", statusChip(playerId));
		values.put("waiting_chip", DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "👤") + " <white>" + eligible + "</white><gray>/" + settings.minPlayers + "</gray>");
		values.put("countdown_chip", "<yellow>⌛</yellow> <white>" + countdownLeft + "s</white>");
		values.put("countdown", String.valueOf(countdownLeft));
		values.put("alive_chip", DeathSwapText.color(DeathSwapText.ACCENT_DANGER, "❤") + " <white>" + alive.size() + "</white><gray>/" + participants.size() + "</gray>");
		values.put("swap_chip", swapChip());
		values.put("swaps_chip", DeathSwapText.color(DeathSwapText.ACCENT_BLUE, "↔") + " <white>#" + swaps + "</white>");
		values.put("timer_chip", DeathSwapText.color(DeathSwapText.ACCENT_TIME, "⌚") + " <white>" + DeathSwapText.clock(elapsedSeconds()) + "</white>");
		values.put("traps_chip", DeathSwapText.color(DeathSwapText.ACCENT_FIRE, "🔥") + " <white>" + roundTraps(playerId) + "</white> <gray>bẫy</gray>");
		values.put("winner_chip", winner == null
			? "<gray>👑 Không ai</gray>"
			: DeathSwapText.color(DeathSwapText.ACCENT_GOLD, "👑") + " " + DeathSwapText.white(winnerName()));
		values.put("sitout_hint", sittingOut.contains(playerId)
			? "<gray>👻 Đang ngồi ngoài</gray> <dark_gray>●</dark_gray> " + DeathSwapText.color(DeathSwapText.COMMAND, "/ds ready")
			: "<gray>👤 Sẵn sàng</gray> <dark_gray>●</dark_gray> " + DeathSwapText.color(DeathSwapText.COMMAND, "/ds sitout"));
		values.put("preparing_stage", DeathSwapText.escape(preparingStage));
	}

	private String statusChip(UUID playerId) {
		if (state == State.PLAYING || state == State.FINISHED) {
			if (alive.contains(playerId)) {
				return DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "❤ Còn sống");
			}
			if (participants.contains(playerId)) {
				return "<gray>☠ Đã bị loại</gray>";
			}
			return "<gray>👻 Khán giả</gray>";
		}
		return sittingOut.contains(playerId) ? "<gray>👻 Ngồi ngoài</gray>" : DeathSwapText.color(DeathSwapText.ACCENT_GREEN, "👤 Sẵn sàng");
	}

	private String swapChip() {
		if (state != State.PLAYING) {
			return "<gray>↔ --:--</gray>";
		}
		long left = secondsUntilSwap();
		if (!swapTimerVisible()) {
			return "<gray>↔</gray> <white>" + hiddenSwapLabel() + "</white>";
		}
		String color = left <= settings.swapWarning ? DeathSwapText.ACCENT_DANGER : DeathSwapText.ACCENT_BLUE;
		return DeathSwapText.color(color, "↔") + " " + DeathSwapText.color(color, DeathSwapText.clock(left));
	}
}
