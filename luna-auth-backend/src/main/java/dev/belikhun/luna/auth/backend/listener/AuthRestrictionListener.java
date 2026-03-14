package dev.belikhun.luna.auth.backend.listener;

import dev.belikhun.luna.auth.backend.service.BackendAuthStateRegistry;
import dev.belikhun.luna.auth.backend.service.BackendAuthSpawnService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthRestrictionListener implements Listener {
	private final JavaPlugin plugin;
	private final BackendAuthStateRegistry stateRegistry;
	private final MiniMessage miniMessage;
	private final PromptSet loginPrompt;
	private final PromptSet registerPrompt;
	private final PromptSet pendingPrompt;
	private final Map<UUID, BossBar> activeBossbars;
	private final Set<String> allowedCommands;
	private final BackendAuthSpawnService spawnService;

	public AuthRestrictionListener(
		JavaPlugin plugin,
		BackendAuthStateRegistry stateRegistry,
		BackendAuthSpawnService spawnService,
		PromptTemplate loginPrompt,
		PromptTemplate registerPrompt,
		PromptTemplate pendingPrompt,
		Set<String> allowedCommands
	) {
		this.plugin = plugin;
		this.stateRegistry = stateRegistry;
		this.spawnService = spawnService;
		this.miniMessage = MiniMessage.miniMessage();
		this.loginPrompt = toComponents(loginPrompt);
		this.registerPrompt = toComponents(registerPrompt);
		this.pendingPrompt = toComponents(pendingPrompt);
		this.activeBossbars = new ConcurrentHashMap<>();
		this.allowedCommands = allowedCommands;
	}

	public void startPromptTask() {
		Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (stateRegistry.isAuthenticated(player.getUniqueId())) {
					hidePrompt(player);
					continue;
				}
				showPrompt(player);
			}
		}, 20L, 20L);
	}

	public void hidePrompt(Player player) {
		BossBar bar = activeBossbars.remove(player.getUniqueId());
		if (bar != null) {
			player.hideBossBar(bar);
		}
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		UUID playerUuid = event.getPlayer().getUniqueId();
		if (!stateRegistry.hasState(playerUuid)) {
			stateRegistry.markUnauthenticated(playerUuid);
		}

		PromptSet prompt = promptFor(playerUuid);
		if (prompt != pendingPrompt) {
			event.getPlayer().sendMessage(prompt.chat());
		}
		if (spawnService.hasSpawn()) {
			Bukkit.getScheduler().runTask(plugin, () -> spawnService.teleportToSpawn(event.getPlayer()));
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		hidePrompt(event.getPlayer());
		stateRegistry.clear(event.getPlayer().getUniqueId());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		Player player = event.getPlayer();
		if (stateRegistry.isAuthenticated(player.getUniqueId())) {
			return;
		}
		Location from = event.getFrom();
		Location to = event.getTo();
		if (to == null) {
			return;
		}
		if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
			return;
		}

		Location corrected = from.clone();
		corrected.setYaw(to.getYaw());
		corrected.setPitch(to.getPitch());
		event.setTo(corrected);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent event) {
		UUID playerUuid = event.getPlayer().getUniqueId();
		if (stateRegistry.isAuthenticated(playerUuid)) {
			return;
		}
		String message = event.getMessage();
		PromptSet prompt = promptFor(playerUuid);
		if (message == null || message.length() < 2) {
			event.setCancelled(true);
			event.getPlayer().sendMessage(prompt.chat());
			return;
		}
		String command = message.substring(1).trim();
		int split = command.indexOf(' ');
		String root = split > -1 ? command.substring(0, split) : command;
		if (allowedCommands.contains(root.toLowerCase())) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(prompt.chat());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event) {
		UUID playerUuid = event.getPlayer().getUniqueId();
		if (stateRegistry.isAuthenticated(playerUuid)) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(promptFor(playerUuid).chat());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPickup(EntityPickupItemEvent event) {
		if (event.getEntity() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onOpenInventory(InventoryOpenEvent event) {
		if (event.getPlayer() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent event) {
		if (!stateRegistry.isAuthenticated(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamageOthers(EntityDamageByEntityEvent event) {
		if (event.getDamager() instanceof Player player && !stateRegistry.isAuthenticated(player.getUniqueId())) {
			event.setCancelled(true);
		}
	}

	private void showPrompt(Player player) {
		PromptSet prompt = promptFor(player.getUniqueId());
		if (prompt == pendingPrompt) {
			hidePrompt(player);
			return;
		}

		BossBar bar = activeBossbars.computeIfAbsent(player.getUniqueId(), ignored -> BossBar.bossBar(
			prompt.bossbar(),
			1f,
			BossBar.Color.YELLOW,
			BossBar.Overlay.PROGRESS
		));
		bar.name(prompt.bossbar());
		player.showBossBar(bar);
		player.sendActionBar(prompt.actionbar());
	}

	private PromptSet promptFor(UUID playerUuid) {
		BackendAuthStateRegistry.AuthState state = stateRegistry.state(playerUuid);
		if (state.authenticated()) {
			return pendingPrompt;
		}
		return switch (state.promptMode()) {
			case REGISTER -> registerPrompt;
			case LOGIN -> loginPrompt;
			case PENDING -> pendingPrompt;
		};
	}

	private PromptSet toComponents(PromptTemplate promptSet) {
		return new PromptSet(
			miniMessage.deserialize(promptSet.bossbar()),
			miniMessage.deserialize(promptSet.actionbar()),
			miniMessage.deserialize(promptSet.chat())
		);
	}

	public record PromptTemplate(String bossbar, String actionbar, String chat) {
	}

	public record PromptSet(Component bossbar, Component actionbar, Component chat) {
	}
}
