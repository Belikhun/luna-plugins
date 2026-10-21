package dev.belikhun.luna.deathswap;

import dev.belikhun.luna.deathswap.gui.DeathSwapMenus;
import dev.belikhun.luna.deathswap.lobby.LobbyService;
import dev.belikhun.luna.deathswap.room.Room;
import dev.belikhun.luna.deathswap.room.RoomManager;
import dev.belikhun.luna.deathswap.world.RoundWorldService;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DeathSwapListener implements Listener {
	private final LunaDeathSwapPlugin plugin;
	private final RoomManager rooms;
	private final LobbyService lobby;
	private final DeathSwapMenus menus;
	private final Map<UUID, Long> notices = new HashMap<>();

	public DeathSwapListener(LunaDeathSwapPlugin plugin, RoomManager rooms, LobbyService lobby, DeathSwapMenus menus) {
		this.plugin = plugin;
		this.rooms = rooms;
		this.lobby = lobby;
		this.menus = menus;
	}

	// ------------------------------------------------------------------
	// joining and leaving

	/**
	 * Decide the join location before the player is placed, as manhunt does:
	 * everybody lands in the lobby, so nobody is ever loaded into a round
	 * world that was deleted while they were away. Runs off the main thread;
	 * the lobby spawn is a copy, safe to hand out.
	 */
	@EventHandler
	public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
		event.setSpawnLocation(lobby.spawn());
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onJoin(PlayerJoinEvent event) {
		rooms.handleJoin(event.getPlayer(), true);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		notices.remove(event.getPlayer().getUniqueId());
		rooms.handleQuit(event.getPlayer());
	}

	// ------------------------------------------------------------------
	// dying

	@EventHandler(priority = EventPriority.HIGH)
	public void onDeath(PlayerDeathEvent event) {
		Component vanilla = event.deathMessage();
		String plain = vanilla == null ? "" : PlainTextComponentSerializer.plainText().serialize(vanilla);
		String line = rooms.handleDeath(event.getPlayer(), plain);
		if (line != null) {
			// shown to the room only, not the whole server
			event.deathMessage(null);
			Room room = rooms.roomByWorld(event.getPlayer().getWorld());
			if (room != null) {
				room.broadcast(line);
				if (room.consumeKeepInventory(event.getPlayer().getUniqueId())) {
					// LifeSwap: losing hearts is the price; the gear stays
					event.setKeepInventory(true);
					event.getDrops().clear();
					event.setKeepLevel(true);
					event.setDroppedExp(0);
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		Location target = rooms.respawnLocation(player);
		event.setRespawnLocation(target);
		Bukkit.getScheduler().runTask(plugin, () -> rooms.handleRespawned(player));
	}

	// ------------------------------------------------------------------
	// the lobby: no damage, no hunger, no building, items locked

	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player player)) {
			return;
		}

		if (!RoundWorldService.isRoundWorld(player.getWorld())) {
			event.setCancelled(true);
			if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
				player.teleport(lobby.spawn());
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamageByEntity(EntityDamageByEntityEvent event) {
		if (event.getDamager() instanceof Firework) {
			event.setCancelled(true);
			return;
		}

		if (!(event.getEntity() instanceof Player victim)) {
			return;
		}
		Room room = rooms.roomByWorld(victim.getWorld());
		if (room != null && room.pvpActive()) {
			return;
		}

		Entity damager = event.getDamager();
		if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player) {
			event.setCancelled(true);
		} else if (damager instanceof Player) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onHunger(FoodLevelChangeEvent event) {
		if (event.getEntity() instanceof Player player && !RoundWorldService.isRoundWorld(player.getWorld())) {
			event.setCancelled(true);
			player.setFoodLevel(20);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		if (lobbyLocked(event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		if (lobbyLocked(event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent event) {
		if (lobbyLocked(event.getPlayer()) || lobby.isLobbyItem(event.getItemDrop().getItemStack())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onSwapHands(PlayerSwapHandItemsEvent event) {
		if (lobby.isLobbyItem(event.getMainHandItem()) || lobby.isLobbyItem(event.getOffHandItem())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onDrag(InventoryDragEvent event) {
		if (lobby.isLobbyItem(event.getOldCursor())) {
			event.setCancelled(true);
		}
	}

	/** Lobby hotbar items do their thing on use. */
	@EventHandler(priority = EventPriority.LOW)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
			return;
		}
		LobbyService.Action action = lobby.actionOf(event.getItem());
		if (action == null) {
			return;
		}
		event.setCancelled(true);

		Player player = event.getPlayer();
		if (!throttle(player, 300)) {
			return;
		}
		Room room = rooms.roomOf(player);
		switch (action) {
			case ROOMS -> menus.openBrowser(player, 0);
			case TOGGLE -> player.performCommand(room != null && room.isSittingOut(player.getUniqueId()) ? "deathswap ready" : "deathswap sitout");
			case STATUS -> menus.openMain(player);
			case LEAVE_ROOM -> menus.confirmLeaveRoom(player);
			case PROFILE -> menus.openProfile(player, player.getUniqueId());
		}
	}

	// ------------------------------------------------------------------
	// menus

	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		if (lobby.isLobbyItem(event.getCurrentItem()) || lobby.isLobbyItem(event.getCursor())) {
			event.setCancelled(true);
		} else if (event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player player
			&& lobby.isLobbyItem(player.getInventory().getItem(event.getHotbarButton()))) {
			event.setCancelled(true);
		}
	}

	// ------------------------------------------------------------------
	// portals

	/** Nether and End would split the players across worlds the swap cannot follow. */
	@EventHandler(ignoreCancelled = true)
	public void onPortal(PlayerPortalEvent event) {
		event.setCancelled(true);
		Player player = event.getPlayer();
		if (RoundWorldService.isRoundWorld(player.getWorld()) && throttle(player, 5000)) {
			DeathSwapText.send(player, DeathSwapText.WARN + "Cổng không gian bị khóa trong DeathSwap.");
		}
	}

	private boolean lobbyLocked(Player player) {
		if (RoundWorldService.isRoundWorld(player.getWorld())) {
			return false;
		}
		return !(player.getGameMode() == GameMode.CREATIVE && player.hasPermission("lunadeathswap.admin"));
	}

	private boolean throttle(Player player, long millis) {
		long now = System.currentTimeMillis();
		Long last = notices.get(player.getUniqueId());
		if (last != null && now - last < millis) {
			return false;
		}
		notices.put(player.getUniqueId(), now);
		return true;
	}
}
